package com.developer.util;

import com.developer.exception.AiGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

/**
 * 对现有 BPMN 做 service task → Automation flow 的定点绑定补丁。
 *
 * <p>每个绑定只改一个 serviceTask 的扩展属性：写（或替换）{@code ap:flowKey}、缺失时补
 * {@code serviceType=ap}、清掉 {@link #LEGACY_AP_KEYS}。其余节点、DI、其它任务一律不动。
 * 语义与设计器 {@code ServiceTaskFlowPanel.saveConfig}（{@code serviceTaskConfigSerializer.ts}）
 * 逐条对齐：只留一个业务键，legacy 键全清。</p>
 *
 * <p>属性容器策略与 {@code AiBpmnActionBindingWriter} 一致：只认设计器能读的两个命名空间；
 * 新建容器时优先沿用文档已声明的前缀，否则以设计器自己的平台命名空间声明 {@code xmlns:custom}。</p>
 */
@Slf4j
public final class BpmnServiceTaskBindingPatcher {

    private static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    /** bpmn-js 侧容器命名空间（customModdle 的 custom_1） */
    static final String CUSTOM_NAMESPACE = "http://custom.bpmn.io/schema";
    /** 流程属性面板写入的容器命名空间（customModdle 的 custom 前缀） */
    static final String PLATFORM_NAMESPACE = "http://workflow.platform/schema/custom";

    /** 与前端 {@code serviceTaskConfigSerializer.LEGACY_AP_KEYS} 同表；改一处必须改另一处。 */
    public static final List<String> LEGACY_AP_KEYS = List.of(
            "ap:flowId", "ap:webhookUrl", "ap:timeoutSeconds", "ap:retryCount", "ap:inputMapping", "ap:outputMapping");

    private BpmnServiceTaskBindingPatcher() {
    }

    /**
     * 解绑：清掉这些 serviceTask 的 {@code ap:flowKey}、{@code serviceType} 与全部 legacy ap:* 键。
     * 供撤销用——把一个"本来没绑定"的任务还原成未绑定，而不是留下半截配置。
     *
     * @return 补丁后的 BPMN 明文；无任务时原样返回
     */
    public static String unbind(String bpmnXml, java.util.Collection<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return bpmnXml;
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new AiGenerationException("AI_BPMN_SERVICE_TASK_NOT_FOUND",
                    "The function unit has no process definition to unbind service tasks on");
        }
        Document document;
        try {
            document = BpmnServiceTaskScanner.parseSecurely(bpmnXml);
        } catch (Exception e) {
            throw new AiGenerationException("AI_EXISTING_BPMN_INVALID",
                    "Existing BPMN could not be parsed: " + e.getMessage());
        }
        for (String taskId : taskIds) {
            Element task = taskId == null ? null : findServiceTask(document, taskId.trim());
            if (task == null) continue;
            Element ext = BpmnServiceTaskScanner.directChild(task, "extensionElements");
            if (ext == null) continue;
            Element properties = designerPropertiesContainer(ext);
            if (properties == null) continue;
            NodeList all = properties.getElementsByTagNameNS("*", "*");
            for (int i = all.getLength() - 1; i >= 0; i--) {
                if (!(all.item(i) instanceof Element el)) continue;
                String local = BpmnServiceTaskScanner.localName(el);
                if (!"property".equals(local) && !"values".equals(local)) continue;
                String name = el.getAttribute("name");
                if (LEGACY_AP_KEYS.contains(name)
                        || BpmnServiceTaskScanner.PROP_FLOW_KEY.equals(name)
                        || BpmnServiceTaskScanner.PROP_SERVICE_TYPE.equals(name)) {
                    el.getParentNode().removeChild(el);
                }
            }
        }
        try {
            return serialize(document);
        } catch (Exception e) {
            throw new AiGenerationException("AI_BPMN_BINDING_FAILED",
                    "Failed to serialize the patched BPMN: " + e.getMessage());
        }
    }

    /**
     * @param bpmnXml         明文 BPMN（调用方负责 smartDecode）
     * @param flowKeyByTaskId serviceTask id → flowKey（非空）
     * @return 补丁后的 BPMN 明文；无绑定时原样返回
     * @throws AiGenerationException 任务不存在（{@code AI_BPMN_SERVICE_TASK_NOT_FOUND}）或 XML 无法解析/序列化
     */
    public static String bind(String bpmnXml, Map<String, String> flowKeyByTaskId) {
        if (flowKeyByTaskId == null || flowKeyByTaskId.isEmpty()) {
            return bpmnXml;
        }
        if (bpmnXml == null || bpmnXml.isBlank()) {
            throw new AiGenerationException("AI_BPMN_SERVICE_TASK_NOT_FOUND",
                    "The function unit has no process definition to bind service tasks on");
        }
        Document document;
        try {
            document = BpmnServiceTaskScanner.parseSecurely(bpmnXml);
        } catch (Exception e) {
            throw new AiGenerationException("AI_EXISTING_BPMN_INVALID",
                    "Existing BPMN could not be parsed: " + e.getMessage());
        }
        for (Map.Entry<String, String> entry : flowKeyByTaskId.entrySet()) {
            String taskId = entry.getKey();
            String flowKey = entry.getValue();
            if (taskId == null || taskId.isBlank() || flowKey == null || flowKey.isBlank()) {
                throw new AiGenerationException("AI_BPMN_SERVICE_TASK_BINDING_INVALID",
                        "serviceTaskId and flowKey must not be blank");
            }
            Element task = findServiceTask(document, taskId.trim());
            if (task == null) {
                throw new AiGenerationException("AI_BPMN_SERVICE_TASK_NOT_FOUND",
                        "No bpmn:serviceTask with id '" + taskId + "' in the process definition");
            }
            bindTask(document, task, flowKey.trim());
        }
        try {
            return serialize(document);
        } catch (Exception e) {
            throw new AiGenerationException("AI_BPMN_BINDING_FAILED",
                    "Failed to serialize the patched BPMN: " + e.getMessage());
        }
    }

    private static Element findServiceTask(Document document, String taskId) {
        NodeList tasks = document.getElementsByTagNameNS("*", "serviceTask");
        for (int i = 0; i < tasks.getLength(); i++) {
            Element task = (Element) tasks.item(i);
            if (taskId.equals(task.getAttribute("id"))) {
                return task;
            }
        }
        return null;
    }

    private static void bindTask(Document document, Element task, String flowKey) {
        Element extensionElements = BpmnServiceTaskScanner.directChild(task, "extensionElements");
        if (extensionElements == null) {
            String prefix = task.getPrefix();
            String qualifiedName = prefix == null || prefix.isBlank() ? "extensionElements" : prefix + ":extensionElements";
            extensionElements = document.createElementNS(BPMN_NAMESPACE, qualifiedName);
            task.insertBefore(extensionElements, task.getFirstChild());
        }
        Element properties = designerPropertiesContainer(extensionElements);
        if (properties == null) {
            properties = createContainer(document, extensionElements);
        }
        String ns = properties.getNamespaceURI();
        String prefix = properties.getPrefix();

        // 清 legacy 键，再写 ap:flowKey（替换或追加），最后确保 serviceType=ap
        NodeList all = properties.getElementsByTagNameNS("*", "*");
        Element flowKeyProp = null;
        boolean hasServiceType = false;
        for (int i = all.getLength() - 1; i >= 0; i--) {
            if (!(all.item(i) instanceof Element el)) continue;
            String local = BpmnServiceTaskScanner.localName(el);
            if (!"property".equals(local) && !"values".equals(local)) continue;
            String name = el.getAttribute("name");
            if (LEGACY_AP_KEYS.contains(name)) {
                el.getParentNode().removeChild(el);
            } else if (BpmnServiceTaskScanner.PROP_FLOW_KEY.equals(name)) {
                flowKeyProp = el;
            } else if (BpmnServiceTaskScanner.PROP_SERVICE_TYPE.equals(name)) {
                hasServiceType = true;
            }
        }
        if (flowKeyProp != null) {
            flowKeyProp.setAttribute("value", flowKey);
        } else {
            properties.appendChild(property(document, ns, prefix, BpmnServiceTaskScanner.PROP_FLOW_KEY, flowKey));
        }
        if (!hasServiceType) {
            properties.appendChild(property(document, ns, prefix, BpmnServiceTaskScanner.PROP_SERVICE_TYPE, "ap"));
        }
    }

    /** 只认设计器 {@code bpmnExtensions.getExtensionProperties()} 会读的两个容器命名空间。 */
    private static Element designerPropertiesContainer(Element extensionElements) {
        NodeList children = extensionElements.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element
                    && "properties".equals(BpmnServiceTaskScanner.localName(element))
                    && (CUSTOM_NAMESPACE.equals(element.getNamespaceURI())
                        || PLATFORM_NAMESPACE.equals(element.getNamespaceURI()))) {
                return element;
            }
        }
        return null;
    }

    /**
     * 新建容器：文档已为平台命名空间或 bpmn-js 命名空间声明了前缀就沿用（避免同一前缀绑两个 URI），
     * 否则按设计器的写法声明 {@code xmlns:custom} = 平台命名空间。
     */
    private static Element createContainer(Document document, Element extensionElements) {
        Element root = document.getDocumentElement();
        String prefix = declaredPrefixFor(root, PLATFORM_NAMESPACE);
        String ns = PLATFORM_NAMESPACE;
        if (prefix == null) {
            prefix = declaredPrefixFor(root, CUSTOM_NAMESPACE);
            if (prefix != null) {
                ns = CUSTOM_NAMESPACE;
            }
        }
        if (prefix == null) {
            prefix = "custom";
            if (root.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, prefix)) {
                // 'custom' 已绑到别的 URI：换一个不冲突的前缀，别把别人的声明改掉
                prefix = "hermes";
            }
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:" + prefix, ns);
        }
        Element properties = document.createElementNS(ns, prefix + ":properties");
        extensionElements.appendChild(properties);
        return properties;
    }

    private static String declaredPrefixFor(Element root, String namespaceUri) {
        var attrs = root.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI())
                    && namespaceUri.equals(attr.getNodeValue())
                    && attr.getLocalName() != null && !"xmlns".equals(attr.getLocalName())) {
                return attr.getLocalName();
            }
        }
        return null;
    }

    private static Element property(Document document, String ns, String prefix, String name, String value) {
        String qualified = prefix == null || prefix.isBlank() ? "property" : prefix + ":property";
        Element property = document.createElementNS(ns, qualified);
        property.setAttribute("name", name);
        property.setAttribute("value", value);
        return property;
    }

    private static String serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
