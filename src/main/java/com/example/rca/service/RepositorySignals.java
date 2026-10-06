package com.example.rca.service;

import com.example.rca.model.CodeModels;
import com.example.rca.model.RcaContext;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Cross-cutting correlation of build declarations, incident text, and observed runtime measurements. */
final class RepositorySignals {
    private static final Set<String> GENERIC=Set.of("spring","boot","starter","data","client","core","common","api","java","apache","org","com","io","junit","test","logging","slf4j","impl");
    private RepositorySignals(){}
    static java.util.List<CodeModels.DependencyInfo> matchingDependencies(RcaContext c){
        String incident=(String.valueOf(c.exceptionType())+" "+String.valueOf(c.errorMessage())+" "+String.valueOf(c.stackTrace())).toLowerCase(Locale.ROOT);
        return c.declaredDependencies().stream().filter(d->tokens(d).stream().anyMatch(incident::contains)).toList();
    }
    static Set<String> tokens(CodeModels.DependencyInfo d){
        return java.util.Arrays.stream((String.valueOf(d.group())+" "+String.valueOf(d.artifact())).toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t->t.length()>2&&!GENERIC.contains(t)).collect(Collectors.toSet());
    }
    static boolean runtimeMetricRelevant(RcaContext.RuntimeMetric m){
        String name=m.name().toLowerCase(Locale.ROOT);
        return Set.of("cpu","memory","heap","hikari","pool","connection","kafka","rabbit","redis","lag","queue","thread","gc","latency","cache","process","system").stream().anyMatch(name::contains);
    }
}
