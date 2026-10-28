package com.open.spring.system;

import org.json.JSONObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

@ControllerAdvice
public class ExceptionHandlerAdvice {
    
    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<String> jsonProcessingException(JsonProcessingException e) {
        // log exception
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("JSON failed to process");
    } 
    
    @ExceptionHandler(JsonMappingException.class)
    public ResponseEntity<String> jsonMappingException(JsonMappingException e) {
        // log exception
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("JSON failed to map");
    }

    // JPA validates entities on save (e.g. Person.PASSWORD_RULE) and throws this.
    // Every endpoint gets the same 400 with the rule's message, so controllers don't check.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<String> constraintViolationException(ConstraintViolationException e) {
        JSONObject body = new JSONObject();
        body.put("error", e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .sorted()
                .collect(java.util.stream.Collectors.joining("; ")));
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }
}
