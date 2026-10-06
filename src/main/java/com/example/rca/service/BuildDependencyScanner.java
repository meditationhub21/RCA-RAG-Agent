package com.example.rca.service;

import com.example.rca.model.CodeModels;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts declared Maven/Gradle coordinates without executing a build script. */
@Component
public class BuildDependencyScanner {
    private static final Pattern GRADLE_STRING=Pattern.compile("(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|annotationProcessor|kapt|classpath)\\s*(?:\\(\\s*)?['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_MAP=Pattern.compile("(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\\s+group\\s*:\\s*['\"]([^'\"]+)['\"]\\s*,\\s*name\\s*:\\s*['\"]([^'\"]+)['\"](?:\\s*,\\s*version\\s*:\\s*['\"]([^'\"]+)['\"])?");
    private static final Pattern CATALOG_ENTRY=Pattern.compile("(?m)^\\s*([\\w.-]+)\\s*=\\s*\\{([^}]+)}");
    private static final Pattern CATALOG_USE=Pattern.compile("\\b(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\\s*(?:\\(\\s*)?libs\\.([\\w.]+)");

    public List<CodeModels.DependencyInfo> scan(List<CodeModels.SourceDocument> documents) {
        var result=new TreeMap<String,CodeModels.DependencyInfo>();
        var catalogs=new HashMap<String,String>();
        for(var file:documents)if(file.path().replace('\\','/').endsWith("libs.versions.toml"))scanCatalog(file.content(),catalogs);
        for(var file:documents) {
            String path=file.path().replace('\\','/');
            try {
                if(path.endsWith("pom.xml")) scanMaven(path,file.content(),result);
                else if(path.endsWith("build.gradle")||path.endsWith("build.gradle.kts")) scanGradle(path,file.content(),catalogs,result);
            } catch(Exception ignored) { /* malformed build files are reported as unavailable inventory */ }
        }
        return List.copyOf(result.values());
    }

    private static void scanMaven(String path,String xml,Map<String,CodeModels.DependencyInfo> out) throws Exception {
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var properties=new HashMap<String,String>();var propertyNodes=document.getElementsByTagName("properties");
        if(propertyNodes.getLength()>0){var nodes=propertyNodes.item(0).getChildNodes();for(int i=0;i<nodes.getLength();i++){Node n=nodes.item(i);if(n instanceof Element e)properties.put(e.getTagName(),e.getTextContent().trim());}}
        var deps=document.getElementsByTagName("dependency");
        for(int i=0;i<deps.getLength();i++) {
            Node node=deps.item(i);if(!(node instanceof Element e))continue;
            String group=child(e,"groupId"),artifact=child(e,"artifactId"),version=resolve(child(e,"version"),properties),scope=child(e,"scope");
            if(artifact.isBlank())continue;
            put(out,new CodeModels.DependencyInfo(group,artifact,version,scope,path,"MAVEN"));
        }
    }
    private static void scanGradle(String path,String text,Map<String,String> catalogs,Map<String,CodeModels.DependencyInfo> out) {
        Matcher m=GRADLE_STRING.matcher(text);
        while(m.find()) {
            String[] c=m.group(2).split(":",-1);if(c.length<2||c[0].equals("project"))continue;
            put(out,new CodeModels.DependencyInfo(c[0],c[1],c.length>2?c[2]:null,m.group(1),path,"GRADLE"));
        }
        m=GRADLE_MAP.matcher(text);
        while(m.find())put(out,new CodeModels.DependencyInfo(m.group(2),m.group(3),m.group(4),m.group(1),path,"GRADLE"));
        m=CATALOG_USE.matcher(text);
        while(m.find()) {
            String alias=m.group(2).replace('.', '-');String coordinate=catalogs.get(alias);
            if(coordinate==null)coordinate=catalogs.get(m.group(2));
            if(coordinate==null)continue;
            String[] c=coordinate.split(":",-1);if(c.length>=2)put(out,new CodeModels.DependencyInfo(c[0],c[1],c.length>2?c[2]:null,m.group(1),path+" -> libs.versions.toml","GRADLE"));
        }
    }
    private static void scanCatalog(String text,Map<String,String> out){Matcher m=CATALOG_ENTRY.matcher(text);while(m.find()){Matcher module=Pattern.compile("module\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(m.group(2));if(module.find())out.put(m.group(1),module.group(1));}}
    private static String child(Element e,String tag){var n=e.getElementsByTagName(tag);return n.getLength()==0?"":n.item(0).getTextContent().trim();}
    private static String resolve(String value,Map<String,String> properties){if(value==null)return null;Matcher m=Pattern.compile("\\$\\{([^}]+)}").matcher(value);StringBuffer b=new StringBuffer();while(m.find())m.appendReplacement(b,Matcher.quoteReplacement(properties.getOrDefault(m.group(1),m.group())));m.appendTail(b);return b.toString();}
    private static void put(Map<String,CodeModels.DependencyInfo> out,CodeModels.DependencyInfo dep){out.putIfAbsent(dep.ecosystem()+":"+dep.group()+":"+dep.artifact()+":"+dep.scope(),dep);}
}
