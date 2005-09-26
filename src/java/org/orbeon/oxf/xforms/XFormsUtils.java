/**
 *  Copyright (C) 2004 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms;

import org.dom4j.*;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.resources.OXFProperties;
import org.orbeon.oxf.resources.URLFactory;
import org.orbeon.oxf.util.Base64;
import org.orbeon.oxf.util.SecureUtils;
import org.orbeon.oxf.xforms.mip.BooleanModelItemProperty;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.orbeon.oxf.xml.dom4j.LocationSAXContentHandler;
import org.orbeon.oxf.processor.ProcessorUtils;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.AttributesImpl;

import javax.xml.transform.TransformerException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class XFormsUtils {

    private static final int BUFFER_SIZE = 1024;
    public static final String DEFAULT_UPLOAD_TYPE = "xs:anyURI";

    /**
     * Adds to <code>target</code> all the attributes in <code>source</code>
     * that are not in the XForms namespace.
     */
//    public static void addNonXFormsAttributes(AttributesImpl target, Attributes source) {
//        for (Iterator i = new XMLUtils.AttributesIterator(source); i.hasNext();) {
//            XMLUtils.Attribute attribute = (XMLUtils.Attribute) i.next();
//            if (!"".equals(attribute.getURI()) &&
//                    !XFormsConstants.XXFORMS_NAMESPACE_URI.equals(attribute.getURI())) {
//                target.addAttribute(attribute.getURI(), attribute.getLocalName(),
//                        attribute.getQName(), ContentHandlerHelper.CDATA, attribute.getValue());
//            }
//        }
//    }

    /**
     * Return the local XForms instance data for the given node, null if not available.
     */
    public static InstanceData getLocalInstanceData(Node node) {
        return node instanceof Element
            ? (InstanceData) ((Element) node).getData()
            : node instanceof Attribute
            ? (InstanceData) ((Attribute) node).getData() : null;
    }

    /**
     * Return the inherited XForms instance data for the given node, null if not available.
     */
    public static InstanceData getInheritedInstanceData(Node node) {
        final InstanceData localInstanceData = getLocalInstanceData(node);
        if (localInstanceData == null)
            return null;

        final InstanceData resultInstanceData;
        try {
            resultInstanceData = (InstanceData) localInstanceData.clone();
        } catch (CloneNotSupportedException e) {
            // This should not happen because the classes cloned are Cloneable
            throw new OXFException(e);
        }

        for (Element currentElement = node.getParent(); currentElement != null; currentElement = currentElement.getParent()) {
            final InstanceData currentInstanceData = getLocalInstanceData(currentElement);

            // Handle readonly inheritance
            if (currentInstanceData.getReadonly().get())
                resultInstanceData.getReadonly().set(true);
            // Handle relevant inheritance
            if (!currentInstanceData.getRelevant().get())
                resultInstanceData.getRelevant().set(false);
        }

        return resultInstanceData;
    }

    /**
     * Recursively decorate all the elements and attributes with default <code>InstanceData</code>.
     */
    public static void setInitialDecoration(Document document) {
        Element rootElement = document.getRootElement();
        Map idToNodeMap = new HashMap();
        setInitialDecorationWorker(rootElement, new int[] {-1}, idToNodeMap);
        ((InstanceData) rootElement.getData()).setIdToNodeMap(idToNodeMap);
    }

    /**
     * Recursively decorate the element and its attributes with default <code>InstanceData</code>.
     */
    public static void setInitialDecoration(Element element) {
        setInitialDecorationWorker(element, null, null);
    }

    private static void setInitialDecorationWorker(Element element, int[] currentId, Map idToNodeMap) {
        // NOTE: ids are only used by the legacy XForms engine
        int elementId = (currentId != null) ? ++currentId[0] : -1;
        if (idToNodeMap != null) {
            idToNodeMap.put(new Integer(elementId), element);
        }

        element.setData(newInstanceData(element.getData(), elementId));

        for (Iterator i = element.attributes().iterator(); i.hasNext();) {
            Attribute attribute = (Attribute) i.next();
            if (!XFormsConstants.XXFORMS_NAMESPACE_URI.equals(attribute.getNamespaceURI())) {
                // NOTE: ids are only used by the legacy XForms engine
                int attributeId = (currentId != null) ? ++currentId[0] : -1;
                if (idToNodeMap != null) {
                    idToNodeMap.put(new Integer(attributeId), attribute);
                }
                attribute.setData(newInstanceData(attribute.getData(), attributeId));
            }
        }
        for (Iterator i = element.elements().iterator(); i.hasNext();) {
            Element child = (Element) i.next();
            setInitialDecorationWorker(child, currentId, idToNodeMap);
        }
    }

    private static InstanceData newInstanceData(Object existingData, int id) {
        if (existingData instanceof LocationData) {
            return new InstanceData((LocationData) existingData, id);
        } else if (existingData instanceof InstanceData) {
            return new InstanceData(((InstanceData) existingData).getLocationData(), id);
        } else {
            return new InstanceData(null, id);
        }
    }

    public static boolean isNameEncryptionEnabled() {
        return OXFProperties.instance().getPropertySet().getBoolean
            (XFormsConstants.XFORMS_ENCRYPT_NAMES_PROPERTY, false).booleanValue();
    }

    public static boolean isHiddenEncryptionEnabled() {
        return OXFProperties.instance().getPropertySet().getBoolean
            (XFormsConstants.XFORMS_ENCRYPT_HIDDEN_PROPERTY, false).booleanValue();
    }

    /**
     * Reconcile "DOM InstanceData annotations" with "attribute annotations"
     */
    public static void addInstanceAttributes(Document instanceDocument) {
        addInstanceAttributes(instanceDocument.getRootElement());
    }

    private static void addInstanceAttributes(final Element element) {
        final Object instanceDataObject = element.getData();
        if (instanceDataObject instanceof InstanceData) {
            final InstanceData instanceData = (InstanceData) element.getData();
            final String invldBnds = instanceData.getInvalidBindIds();
            updateAttribute(element, XFormsConstants.XXFORMS_INVALID_BIND_IDS_ATTRIBUTE_QNAME, invldBnds, null);

            // Reconcile boolean model item properties
            reconcileBoolean(instanceData.getReadonly(), element, XFormsConstants.XXFORMS_READONLY_ATTRIBUTE_QNAME, false);
            reconcileBoolean(instanceData.getRelevant(), element, XFormsConstants.XXFORMS_RELEVANT_ATTRIBUTE_QNAME, true);
            reconcileBoolean(instanceData.getRequired(), element, XFormsConstants.XXFORMS_REQUIRED_ATTRIBUTE_QNAME, false);
            reconcileBoolean(instanceData.getValid(), element, XFormsConstants.XXFORMS_VALID_ATTRIBUTE_QNAME, true);
        }

        for (final Iterator i = element.elements().iterator(); i.hasNext();) {
            final Object o = i.next();
            addInstanceAttributes((Element) o);
        }
    }

    private static void reconcileBoolean(final BooleanModelItemProperty prp, final Element elt, final QName qnm, final boolean defaultValue) {
        final String currentBooleanValue;
        if (prp.hasChangedFromDefault()) {
            final boolean b = prp.get();
            currentBooleanValue = Boolean.toString(b);
        } else {
            currentBooleanValue = null;
        }
        updateAttribute(elt, qnm, currentBooleanValue, Boolean.toString(defaultValue));
    }

    private static void updateAttribute(final Element elt, final QName qnam, final String currentValue, final String defaultValue) {
        Attribute attr = elt.attribute(qnam);
        if (((currentValue == null) || (currentValue != null && currentValue.equals(defaultValue))) && attr != null) {
            elt.remove(attr);
        } else if (currentValue != null && !currentValue.equals(defaultValue)) {
            // Add a namespace declaration if necessary
            final String pfx = qnam.getNamespacePrefix();
            final String qnURI = qnam.getNamespaceURI();
            final Namespace ns = elt.getNamespaceForPrefix(pfx);
            final String nsURI = ns == null ? null : ns.getURI();
            if (ns == null) {
                elt.addNamespace(pfx, qnURI);
            } else if (!nsURI.equals(qnURI)) {
                final InstanceData instDat = XFormsUtils.getLocalInstanceData(elt);
                final LocationData locDat = instDat.getLocationData();
                throw new ValidationException("Cannot add attribute to node with 'xxforms' prefix"
                        + " as the prefix is already mapped to another URI", locDat);
            }
            // Add attribute
            if (attr == null) {
                attr = Dom4jUtils.createAttribute(elt, qnam, currentValue);
                final LocationData ld = (LocationData) attr.getData();
                final InstanceData instDat = new InstanceData(ld);
                attr.setData(instDat);
                elt.add(attr);
            } else {
                attr.setValue(currentValue);
            }
        }
    }

    public static void removeInstanceAttributes(Document instanceDocument) {
        Visitor visitor = new VisitorSupport() {
            public void visit(Element node) {
                List newAttributes = new ArrayList();
                for (Iterator i = node.attributeIterator(); i.hasNext();) {
                    Attribute attr = (Attribute) i.next();
                    if (!XFormsConstants.XXFORMS_NAMESPACE_URI.equals(attr.getNamespaceURI()))
                        newAttributes.add(attr);

                }
                node.setAttributes(newAttributes);
            }
        };
        instanceDocument.accept(visitor);
    }

    /**
     * Iterate through all data nodes of the instance document and call the walker on each of them.
     *
     * @param instanceDocument
     * @param instanceWalker
     */
    public static void iterateInstanceData(Document instanceDocument, InstanceWalker instanceWalker) {
        iterateInstanceData(instanceDocument.getRootElement(), instanceWalker);
    }

    private static void iterateInstanceData(Element element, InstanceWalker instanceWalker) {
        instanceWalker.walk(element, getLocalInstanceData(element), getInheritedInstanceData(element));

        for (Iterator i = element.attributes().iterator(); i.hasNext();) {
            final Attribute attribute = (Attribute) i.next();
            instanceWalker.walk(attribute, getLocalInstanceData(attribute), getInheritedInstanceData(attribute));
        }
        for (Iterator i = element.elements().iterator(); i.hasNext();) {
            final Element child = (Element) i.next();
            iterateInstanceData(child, instanceWalker);
        }
    }

    public static String encodeXML(PipelineContext pipelineContext, org.w3c.dom.Node node) {

        try {
            return encodeXML(pipelineContext, TransformerUtils.domToDom4jDocument(node), getEncryptionKey());
        } catch (TransformerException e) {
            throw new OXFException(e);
        }
    }

    public static String encodeXMLAsDOM(PipelineContext pipelineContext, Document instance) {
        return encodeXML(pipelineContext, instance, getEncryptionKey());
    }

    public static String encodeXML(PipelineContext pipelineContext, Document instance, String encryptionPassword) {
        try {
            ByteArrayOutputStream gzipByteArray = new ByteArrayOutputStream();
            GZIPOutputStream gzipOutputStream = null;
            gzipOutputStream = new GZIPOutputStream(gzipByteArray);
            gzipOutputStream.write(Dom4jUtils.domToString(instance, false, false).getBytes("utf-8"));
            gzipOutputStream.close();
            String result = Base64.encode(gzipByteArray.toByteArray());
            if (encryptionPassword != null)
                result = SecureUtils.encrypt(pipelineContext, encryptionPassword, result);
            return result;
        } catch (IOException e) {
            throw new OXFException(e);
        }
    }

    public static org.w3c.dom.Document decodeXMLAsDOM(PipelineContext pipelineContext, String encodedXML) {
        try {
            return TransformerUtils.dom4jToDomDocument(XFormsUtils.decodeXML(pipelineContext, encodedXML));
        } catch (TransformerException e) {
            throw new OXFException(e);
        }
    }

    public static Document decodeXML(PipelineContext pipelineContext, String encodedXML) {
        return decodeXML(pipelineContext, encodedXML, getEncryptionKey());
    }

    public static Document decodeXML(PipelineContext pipelineContext, String encodedXML, String encryptionPassword) {
        try {
            // Get raw text
            String xmlText;
            {
                if (encryptionPassword != null)
                    encodedXML = SecureUtils.decrypt(pipelineContext, encryptionPassword, encodedXML);
                ByteArrayInputStream compressedData = new ByteArrayInputStream(Base64.decode(encodedXML));
                StringBuffer xml = new StringBuffer();
                byte[] buffer = new byte[1024];
                GZIPInputStream gzipInputStream = new GZIPInputStream(compressedData);
                int size;
                while ((size = gzipInputStream.read(buffer)) != -1)
                    xml.append(new String(buffer, 0, size, "utf-8"));
                xmlText = xml.toString();
            }
            // Parse XML and return documents
            LocationSAXContentHandler saxContentHandler = new LocationSAXContentHandler();
            XMLUtils.stringToSAX(xmlText, null, saxContentHandler, false);
            return saxContentHandler.getDocument();
        } catch (IOException e) {
            throw new OXFException(e);
        }
    }

    public static String getEncryptionKey() {
        if (XFormsUtils.isHiddenEncryptionEnabled())
            return OXFProperties.instance().getPropertySet().getString(XFormsConstants.XFORMS_PASSWORD_PROPERTY);
        else
            return null;
    }

    public static String retrieveSrcValue(String src) throws IOException {
        URL url = URLFactory.createURL(src);

        // Load file into buffer
        InputStreamReader reader = new InputStreamReader(url.openStream());
        try {
            StringBuffer value = new StringBuffer();
            char[] buff = new char[BUFFER_SIZE];
            int c = 0;
            while ((c = reader.read(buff, 0, BUFFER_SIZE - 1)) != -1)
                value.append(buff, 0, c);
            return value.toString();
        } finally {
            if (reader != null)
                reader.close();
        }
    }

    public static String convertUploadTypes(PipelineContext pipelineContext, String value, String currentType, String newType) {
        if (currentType.equals(newType))
            return value;
        if (ProcessorUtils.supportedBinaryTypes.get(currentType) == null)
            throw new UnsupportedOperationException("Unsupported type: " + currentType);
        if (ProcessorUtils.supportedBinaryTypes.get(newType) == null)
            throw new UnsupportedOperationException("Unsupported type: " + newType);

        if (currentType.equals(XMLConstants.XS_BASE64BINARY_QNAME.getQualifiedName())) {
            // Convert from xs:base64Binary to xs:anyURI
            return XMLUtils.base64BinaryToAnyURI(pipelineContext, value);
        } else {
            // Convert from xs:anyURI to xs:base64Binary
            return XMLUtils.anyURIToBase64Binary(value);
        }
    }

    public static interface InstanceWalker {
        public void walk(Node node, InstanceData localInstanceData, InstanceData inheritedInstanceData);
    }
}
