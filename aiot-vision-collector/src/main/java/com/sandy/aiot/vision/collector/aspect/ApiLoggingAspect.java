package com.sandy.aiot.vision.collector.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.ui.Model;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class ApiLoggingAspect {

    private final ObjectMapper objectMapper;

    @Around("within(com.sandy.aiot.vision.collector.controller..*)")
    public Object logApiCall(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs != null ? attrs.getRequest() : null;
        String method = request != null ? request.getMethod() : "";
        String uri = request != null ? request.getRequestURI() : "";
        String query = request != null ? request.getQueryString() : null;

        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Object[] args = pjp.getArgs();
        String[] paramNames = sig.getParameterNames();

        Map<String, Object> argMap = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof HttpServletRequest || a instanceof HttpServletResponse || a instanceof Model) continue;
            String name = paramNames != null && i < paramNames.length ? paramNames[i] : ("arg" + i);
            argMap.put(name, a);
        }

        log.info("API Request: method={} uri={} query={} handler={} args={}", method, uri, query, sig.toShortString(), toJson(argMap));

        Object result = null;
        Throwable error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (error == null) {
                if (result instanceof ResponseEntity<?> re) {
                    log.info("API Response: method={} uri={} handler={} status={} durationMs={} body={}", method, uri, sig.toShortString(), re.getStatusCode(), cost, toJson(re.getBody()));
                } else {
                    log.info("API Response: method={} uri={} handler={} durationMs={} result={}", method, uri, sig.toShortString(), cost, toJson(result));
                }
            } else {
                log.error("API Error: method={} uri={} handler={} durationMs={} errorType={} message={}", method, uri, sig.toShortString(), cost, error.getClass().getSimpleName(), error.getMessage());
            }
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return "null";
        try {
            String s = objectMapper.writeValueAsString(obj);
            int max = 2000;
            if (s.length() > max) {
                return s.substring(0, max) + "...(" + (s.length() - max) + " more chars)";
            }
            return s;
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}

