package com.example.rca.parser;

import com.example.rca.model.CodeModels;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
import java.util.*;

@Component
public class JavaAstParser {
    public List<CodeModels.TypeInfo> parse(Path path, String relativePath, String source) {
        var configuration=new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        var unit=new JavaParser(configuration).parse(source).getResult()
                .orElseThrow(()->new IllegalArgumentException("Could not parse Java source: "+relativePath));
        var pkg=unit.getPackageDeclaration().map(p->p.getNameAsString()).orElse("");
        var out=new ArrayList<CodeModels.TypeInfo>();
        for(var type:unit.getTypes()) {
            String kind=type instanceof ClassOrInterfaceDeclaration c && c.isInterface()?"interface":
                    type instanceof EnumDeclaration?"enum":type instanceof RecordDeclaration?"record":"class";
            int line=type.getBegin().map(p->p.line).orElse(1);
            var methods=new ArrayList<CodeModels.MethodInfo>();
            for(var method:type.getMembers().stream().filter(m->m instanceof MethodDeclaration || m instanceof ConstructorDeclaration).toList()) {
                String name; String signature; String body;
                int start=method.getBegin().map(p->p.line).orElse(line), end=method.getEnd().map(p->p.line).orElse(start);
                List<String> calls; List<String> thrown;
                if(method instanceof MethodDeclaration m) {
                    name=m.getNameAsString(); signature=m.getDeclarationAsString(false,false,false); body=m.getBody().map(Object::toString).orElse("");
                    calls=m.findAll(MethodCallExpr.class).stream().map(c->c.getNameAsString()).distinct().toList(); thrown=m.getThrownExceptions().stream().map(Object::toString).toList();
                } else {
                    var c=(ConstructorDeclaration)method; name="<init>"; signature=c.getDeclarationAsString(false,false,false); body=c.getBody().toString();
                    calls=c.findAll(MethodCallExpr.class).stream().map(x->x.getNameAsString()).distinct().toList(); thrown=c.getThrownExceptions().stream().map(Object::toString).toList();
                }
                methods.add(new CodeModels.MethodInfo(name,signature,start,end,body,calls,thrown));
            }
            var ext=type instanceof ClassOrInterfaceDeclaration c?c.getExtendedTypes().stream().map(Object::toString).toList():List.<String>of();
            var impl=type instanceof ClassOrInterfaceDeclaration c?c.getImplementedTypes().stream().map(Object::toString).toList():List.<String>of();
            var dependencies=type.getMembers().stream().filter(FieldDeclaration.class::isInstance).map(FieldDeclaration.class::cast)
                    .flatMap(f->f.getVariables().stream()).map(v->v.getType().toString()).map(t->t.replaceAll("<.*>","")).distinct().toList();
            out.add(new CodeModels.TypeInfo(type.getNameAsString(),kind,pkg,relativePath,line,
                    type.getAnnotations().stream().map(Object::toString).toList(),ext,impl,dependencies,methods,type.toString()));
        }
        return out;
    }
}
