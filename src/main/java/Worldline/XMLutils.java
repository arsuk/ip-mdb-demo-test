package Worldline;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
/** 
 * Class for XML handling procedures used by multiple classes<br/>
 * These are wrapper procedures around the Java XML Document class. 
 * @author Allan Smith
 * 
 */
public class XMLutils {
	private static final Logger logger = LogManager.getLogger(XMLutils.class);
	
	static DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
	static TransformerFactory tf = TransformerFactory.newInstance();

	private XMLutils() {
		// hide constructor for Utility-class
	}
	// XML Document help methods - used by other classes as well
	public static byte[] getTemplate(String file) {
		byte[] docBytes=null;
		try {	// Try getting file from 'current dir' (allows user to define own XML)
			docBytes=Files.readAllBytes(Paths.get(file));
		} catch (IOException e) {
			try {	// Try getting file from war file resource dir WEB-INF/classes
				logger.warn("Using class template "+file);
			    InputStream is = XMLutils.class.getClassLoader().getResourceAsStream(file);
			    docBytes = new byte[is.available()];
			    is.read(docBytes);
				// docBytes=XMLutils.class.getClassLoader().getResourceAsStream(file).readAllBytes();	// Java 9 version
			} catch (Exception ie) {};
			if (docBytes==null)
				logger.error("XML document file error: "+e);
		}
		return docBytes;
	}
	public static Document bytesToDoc(byte[]text) {
		Document doc=null;
		try {
			DocumentBuilder builder = factory.newDocumentBuilder();

			ByteArrayInputStream input = new ByteArrayInputStream(text);
			doc = builder.parse(input);
			input.close();
		} catch (SAXException | IOException | ParserConfigurationException e) {
			logger.error("XML document parse error: "+e);
		}
		return doc;
	}
	public static Document stringToDoc (String xml) {
		try {
			return bytesToDoc(xml.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			logger.error("XML document error: ",e);
			return null;
		}		
	}
	public static String documentToString(Document doc) {
		try {			
			StringWriter sw = new StringWriter();
			tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			tf.setAttribute("indent-number", new Integer(1));
			Transformer transformer = tf.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
			transformer.setOutputProperty(OutputKeys.METHOD, "xml");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "1");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.transform(new DOMSource(doc), new StreamResult(sw));
			return sw.toString();
		} catch (TransformerException ex) {
			throw new IllegalStateException("XML to String failed with a TransformerException", ex);
		}
	}
	public static String getElementValue(Document doc, String tagName) {
		return getElementValue(doc.getDocumentElement(),tagName);
	}
	public static String getElementValue(Element element, String tagName) {
		NodeList nl=element.getElementsByTagName(tagName);
		if (nl.getLength()>0) return nl.item(0).getTextContent();
		else return null;
	}
	public static void setElementValue(Document doc, String tagName,String value) {
		setElementValue(doc.getDocumentElement(),tagName,value);
	}
	public static void setElementValue(Element element, String tagName, String value) {
		if (value==null) return; // In case old doc getElementValue returned null do not set new doc
		NodeList nl=element.getElementsByTagName(tagName);
		if (nl.getLength()>0) nl.item(0).setTextContent(value);
	}
	public static void copyElementValues(Document doc1, Document doc2, String tagName) {
		NodeList nl1=doc1.getElementsByTagName(tagName);
		NodeList nl2=doc2.getElementsByTagName(tagName);
		for(int i=0;i<nl1.getLength()&&i<nl2.getLength();i++)
			nl2.item(i).setTextContent(nl1.item(i).getTextContent());
	}
	public static Element getElement(Element element,String tagName) {
		NodeList nl=element.getElementsByTagName(tagName);
		if (nl.getLength()>0) return (Element)nl.item(0);
		else return null;
	}
	public static Element getElement(Document doc,String tagName) {
		NodeList nl=doc.getElementsByTagName(tagName);
		if (nl.getLength()>0) return (Element)nl.item(0);
		else return null;
	}
}

