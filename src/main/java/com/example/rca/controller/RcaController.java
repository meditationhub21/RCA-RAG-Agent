package com.example.rca.controller;

import com.example.rca.model.ApiModels.RcaRequest;
import com.example.rca.model.ApiModels.RcaResponse;
import com.example.rca.service.RcaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@RestController
@Tag(name = "Root cause analysis", description = "Evidence-grounded RCA for repository failures")
public class RcaController {
    private static final Pattern EXCEPTION=Pattern.compile("([\\w$]+(?:\\.[\\w$]+)*(?:Exception|Error|Throwable))(?::\\s*([^\\r\\n]*))?");
    private final RcaService rca;
    public RcaController(RcaService rca){this.rca=rca;}

    @PostMapping(value="/rca",consumes=MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Analyze an error and stack trace", description = "Builds repository, graph, Git, vector, and historical incident context, then returns an evidence-grounded RCA and a source-validated suggested patch when available.")
    public RcaResponse investigate(@RequestBody RcaRequest request){
        return rca.investigate(request.repositoryName(),request.errorMessage(),request.stackTrace());
    }

    @PostMapping(value="/rca",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Analyze an uploaded log file", description = "Extracts an exception summary and stack trace from an uploaded UTF-8 log file, then runs the same RCA workflow as the JSON endpoint.")
    public RcaResponse investigateFile(
            @Parameter(description = "Repository name used during synchronization", required = true, example = "petclinic") @RequestParam String repositoryName,
            @Parameter(description = "UTF-8 application log file", required = true) @RequestParam("file") MultipartFile file){
        if(file.isEmpty()) throw new IllegalArgumentException("Uploaded log file is empty");
        try {
            String log=new String(file.getBytes(),StandardCharsets.UTF_8);
            var matcher=EXCEPTION.matcher(log);
            String errorMessage=matcher.find()
                    ? matcher.group(1)+(matcher.group(2)==null?"":": "+matcher.group(2).trim())
                    : log.lines().filter(line->!line.isBlank()).findFirst().orElse("Uploaded error log");
            return rca.investigate(repositoryName,errorMessage,log);
        } catch(IOException e) {
            throw new IllegalArgumentException("Could not read uploaded log file",e);
        }
    }
}