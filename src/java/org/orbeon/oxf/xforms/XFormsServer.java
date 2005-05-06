/**
 *  Copyright (C) 2005 Orbeon, Inc.
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

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.QName;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.processor.ProcessorInputOutputInfo;
import org.orbeon.oxf.processor.ProcessorOutput;
import org.orbeon.oxf.util.PooledXPathExpression;
import org.orbeon.oxf.util.XPathCache;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.orbeon.saxon.dom4j.DocumentWrapper;
import org.orbeon.saxon.xpath.XPathException;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.util.*;

/**
 * The XForms Server processor handles client requests, including events and actions.
 */
public class XFormsServer extends ProcessorImpl {

    //static private Logger logger = LoggerFactory.createLogger(XFormsServer.class);

    private static final String INPUT_REQUEST = "request";
    private static final String OUTPUT_RESPONSE = "response";

    public static final Map XFORMS_NAMESPACES = new HashMap();

    static {
        XFORMS_NAMESPACES.put(XFormsConstants.XFORMS_SHORT_PREFIX, XFormsConstants.XFORMS_NAMESPACE_URI);
        XFORMS_NAMESPACES.put(XFormsConstants.XML_EVENTS_PREFIX, XFormsConstants.XML_EVENTS_NAMESPACE_URI);
        XFORMS_NAMESPACES.put(XFormsConstants.XXFORMS_SHORT_PREFIX, XFormsConstants.XXFORMS_NAMESPACE_URI);
    }

    public XFormsServer() {
        addInputInfo(new ProcessorInputOutputInfo(INPUT_REQUEST));
        addOutputInfo(new ProcessorInputOutputInfo(OUTPUT_RESPONSE));
    }

    public ProcessorOutput createOutput(String name) {
        ProcessorOutput output = new ProcessorImpl.CacheableTransformerOutputImpl(getClass(), name) {
            public void readImpl(final PipelineContext pipelineContext, ContentHandler contentHandler) {

                // Extract information from request
                Document requestDocument = readInputAsDOM4J(pipelineContext, INPUT_REQUEST);
                Document eventDocument = Dom4jUtils.createDocument(requestDocument.getRootElement().element(new QName("event", XFormsConstants.XXFORMS_NAMESPACE)));

                String encodedControlsString = requestDocument.getRootElement().element(new QName("controls", XFormsConstants.XXFORMS_NAMESPACE)).getTextTrim();

                Element encodedModelsElement = requestDocument.getRootElement().element(new QName("models", XFormsConstants.XXFORMS_NAMESPACE));
                String encodedModelsString = encodedModelsElement.getTextTrim();

                Element encodedInstancesElement = requestDocument.getRootElement().element(new QName("instances", XFormsConstants.XXFORMS_NAMESPACE));
                String encodedInstancesString = encodedInstancesElement.getTextTrim();

                // Create and initialize XForms engine from encoded data
                XFormsContainingDocument containingDocument = createXFormsEngine(pipelineContext, encodedControlsString,
                        encodedModelsString, encodedInstancesString);

                // Run action if any
                XFormsContainingDocument.EventResult eventResult = null;
                {
                    final Element eventElement = eventDocument.getRootElement();
                    String controlId = eventElement.attributeValue("source-control-id");
                    String eventName = eventElement.attributeValue("name");
                    String value = eventElement.attributeValue("value");

                    if (controlId != null && eventName != null) {
                        // An event is passed
                        eventResult = containingDocument.executeEvent(pipelineContext, controlId, eventName, value);
                    } else if (!(controlId == null && eventName == null)) {
                        throw new OXFException("<event> element must either have source-control-id and name attributes, or no attribute.");
                    }
                }

                // Create resulting document
                try {
                    ContentHandlerHelper ch = new ContentHandlerHelper(contentHandler);
                    ch.startDocument();
                    contentHandler.startPrefixMapping("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI);
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "event-response");

                    // Output new controls values
                    {
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control-values");

                        {
                            Document controlsDocument = containingDocument.getxFormsControls().getControlsDocument();
                            PooledXPathExpression xpathExpression =
                                    XPathCache.getXPathExpression(pipelineContext, new DocumentWrapper(controlsDocument, null).wrap(controlsDocument), "/xxf:controls//(xf:input|xf:secret|xf:textarea|xf:output|xf:upload|xf:range|xf:trigger|xf:submit|xf:select|xf:select1)[@ref]", XFORMS_NAMESPACES);
                            try {
                                for (Iterator i = xpathExpression.evaluate().iterator(); i.hasNext();) {
                                    Element controlElement = (Element) i.next();

                                    String controlId = controlElement.attributeValue("id");

                                    // Set current binding for control element
                                    XFormsControls xFormsControls = containingDocument.getxFormsControls();
                                    xFormsControls.setBinding(pipelineContext, controlElement);

                                    // Get current control value
                                    XFormsInstance instance = xFormsControls.getCurrentInstance();
                                    String controlValue = instance.getValueForNode(xFormsControls.getCurrentSingleNode());

                                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control", new String[]{"id", controlId, "value", controlValue});
                                }
                            } catch (XPathException e) {
                                throw new OXFException(e);
                            } finally {
                                if (xpathExpression != null)
                                    xpathExpression.returnToPool();
                            }
                        }

                        ch.endElement();
                    }

                    // Output updated instances
                    {
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "instances");

                        Document instancesDocument = Dom4jUtils.createDocument();

                        // Move all instances under a single root element
                        {
                            instancesDocument.addElement("instances");
                            Element instancesElement = instancesDocument.getRootElement();

                            for (Iterator i = containingDocument.getModels().iterator(); i.hasNext();) {
                                XFormsModel model = (XFormsModel) i.next();

                                instancesElement.add(model.getInstance().getDocument().getRootElement().detach());
                            }
                        }

                        // Encode all instances
                        String encodedInstance = XFormsUtils.encodeXMLAsDOM(pipelineContext, instancesDocument);
                        ch.text(encodedInstance);

                        ch.endElement();
                    }

                    // Output divs
                    {
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "divs");

                        if (eventResult != null) {
                            if (eventResult.getDivsToHide() != null) {
                                for (Iterator i = eventResult.getDivsToHide().iterator(); i.hasNext();) {
                                    String caseId = (String) i.next();
                                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "div", new String[]{"id", caseId, "visibility", "hidden"});
                                }
                            }
                            if (eventResult.getDivsToShow() != null) {
                                for (Iterator i = eventResult.getDivsToShow().iterator(); i.hasNext();) {
                                    String caseId = (String) i.next();
                                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "div", new String[]{"id", caseId, "visibility", "visible"});
                                }
                            }
                        }

                        ch.endElement();
                    }

                    ch.endElement();
                    contentHandler.endPrefixMapping("xxf");
                    ch.endDocument();
                } catch (SAXException e) {
                    throw new OXFException(e);
                }
            }
        };
        addOutput(name, output);
        return output;
    }

    public static XFormsContainingDocument createXFormsEngine(PipelineContext pipelineContext, String encodedControlsString,
                                                              String encodedModelsString, String encodedInstancesString) {
        // Get controls
        Document controlsDocument = XFormsUtils.decodeXML(pipelineContext, encodedControlsString);

        // Get models
        final List models = new ArrayList();
        {
            Document modelsDocument = XFormsUtils.decodeXML(pipelineContext, encodedModelsString);

            for (Iterator i = modelsDocument.getRootElement().elements().iterator(); i.hasNext();) {
                Element modelElement = (Element) i.next();

                Document modelDocument = Dom4jUtils.createDocument(modelElement);
                XFormsModel model = new XFormsModel(modelDocument);
                models.add(model);
            }
        }

        // Create XForms Engine
        XFormsContainingDocument containingDocument = new XFormsContainingDocument(models, controlsDocument);

        // Get instances
        boolean isInitializeEvent;
        {
            int instancesCount = 0;
            if (!"".equals(encodedInstancesString)) {
                Document instancesDocument = XFormsUtils.decodeXML(pipelineContext, encodedInstancesString);


                for (Iterator i = instancesDocument.getRootElement().elements().iterator(); i.hasNext();) {
                    Element instanceElement = (Element) i.next();

                    Document instanceDocument = Dom4jUtils.createDocument(instanceElement);
                    ((XFormsModel) containingDocument.getModels().get(instancesCount)).setInstanceDocument(pipelineContext, instanceDocument);

                    instancesCount++;
                }
                // Number of instances must be zero or match number of models
                if (instancesCount != 0 && containingDocument.getModels().size() != instancesCount)
                    throw new OXFException("Number of instances (" + instancesCount + ") doesn't match number of models (" + containingDocument.getModels().size() + ").");
            }
            // Initialization will take place if no instances are provided
            isInitializeEvent = instancesCount == 0;
        }

        // Initialize XForms Engine
        containingDocument.initialize(pipelineContext);
        if (isInitializeEvent)
            containingDocument.dispatchEvent(pipelineContext, XFormsEvents.XXFORMS_INITIALIZE);
        else
            containingDocument.dispatchEvent(pipelineContext, XFormsEvents.XXFORMS_INITIALIZE_CONTROLS);

        return containingDocument;
    }
}
