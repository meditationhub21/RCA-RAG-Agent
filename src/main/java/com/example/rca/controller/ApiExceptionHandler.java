package com.example.rca.controller;

import com.example.rca.model.ApiModels.ApiError;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException e){return ResponseEntity.badRequest().body(new ApiError("BAD_REQUEST",e.getMessage()));}
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> failed(IllegalStateException e){return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError("OPERATION_FAILED",e.getMessage()));}
}
