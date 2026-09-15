package com.developer.util;

import com.developer.exception.AiGenerationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读出 BPMN 里的每个 service task 及其 Automation 绑定（{@code serviceType} / {@code ap:flowKey} /
 * legacy {@code ap:flowId}）。
 *
 * <p>属性识别只看 {@code name} 属性、不看命名空间——与引擎
 * {@code ProcessDeploymentManager} 按 localName 递归扫描扩展属性的做法一致，也与前端
 * {@code utils/serviceTaskBindings.ts} 同规则（两边互为镜像）。子流程内的 service task 一并计入。</p>
 */
public final class BpmnServiceTaskScanner {

    public static final String PROP_SERVICE_TYPE = "serviceType";
    public static final String PROP_FLOW_KEY = "ap:flowKey";
    public static final String PROP_LEGACY_FLOW_ID = "ap:flowId";

    /** 一个 service task 的绑定视图；未配置的字段为 null。 */
    public record ServiceTaskInfo(String id, String name, String serviceType, String flowKey, String legacyFlowId) {}

    private BpmnServiceTaskScanner() {
    }

    /**
     * @param bpmnXml 明文或 Base64 的 BPMN
     * @return 按文档顺序的 service task 列表；空 XML 返回空列表
     * @throws AiGenerationException XML 无法解析（{@code AI_EXISTING_BPMN_INVALID}）
     */
    public static List<ServiceTaskInfo> scan(String bpmnXml) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            return List.of();
        }
        Document document;
        try {
            document = parseSecurely(XmlEncodingUtil.smartDecode(bpmnXml));
        } catch (Exception e) {
            throw new AiGenerationException("AI_EXISTING_BPMN_INVALID",
                    "Existing BPMN could not be parsed: " + e.getMessage());
        }
        List<ServiceTaskInfo> out = new ArrayList<>();
        NodeList tasks = document.getElementsByTagNameNS("*", "serviceTask");
        for (int i = 0; i < tasks.getLength(); i++) {
            Element task = (Element) tasks.item(i);
            Map<String, String> props = readExtensionProperties(task);
            out.add(new ServiceTaskInfo(
                    task.getAttribute("id"),
                    blankToNull(task.getAttribute("name")),
                    blankToNull(props.get(PROP_SERVICE_TYPE)),
                    blankToNull(props.get(PROP_FLOW_KEY)),
                    blankToNull(props.get(PROP_LEGACY_FLOW_ID))));
        }
        return out;
    }

    /** task 直接子元素 extensionElements 之下，所有带 name 的 property / values 元素（任意命名空间）。 */
    static Map<String, String> readExtensionProperties(Element task) {
        Map<String, String> props = new LinkedHashMap<>();
        Element ext = directChild(task, "extensionElements");
        if (ext == null) {
            return props;
        }
        NodeList all = ext.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            if (all.item(i) instanceof Element el
                    && ("property".equals(localName(el)) || "values".equals(localName(el)))
                    && el.hasAttribute("name")) {
                props.putIfAbsent(el.getAttribute("name"), el.getAttribute("value"));
            }
        }
        return props;
    }

    static Element directChild(Element parent, String expectedLocalName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && expectedLocalName.equals(localName(element))) {
                return element;
            }
        }
        return null;
    }

    static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }

    /** 与 {@code AiBpmnActionBindingWriter#parseSecurely} 同一组 XXE 防护特性。 */
    static Document parseSecurely(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
