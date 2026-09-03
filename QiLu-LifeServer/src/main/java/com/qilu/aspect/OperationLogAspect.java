package com.qilu.aspect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.annotation.Log;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.OperationLog;
import com.qilu.service.IOperationLogService;
import com.qilu.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Aspect
@Component
public class OperationLogAspect {

    private static final int PARAMS_MAX_LENGTH = 2048;
    private static final int ERROR_MAX_LENGTH = 512;

    @Resource
    private IOperationLogService operationLogService;

    @Resource
    private ObjectMapper objectMapper;

    @Around("@annotation(operationLog)")
    public Object recordOperationLog(ProceedingJoinPoint joinPoint, Log operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = null;
        Throwable thrown = null;
        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            thrown = ex;
            throw ex;
        } finally {
            saveLog(joinPoint, operationLog, result, thrown, System.currentTimeMillis() - start);
        }
    }

    private void saveLog(ProceedingJoinPoint joinPoint, Log logAnnotation, Object result, Throwable thrown, long costTime) {
        try {
            HttpServletRequest request = getCurrentRequest();
            UserDTO user = UserHolder.getUser();
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();

            OperationLog log = new OperationLog()
                    .setUserId(user == null ? null : user.getId())
                    .setUserRole(user == null ? null : user.getRole())
                    .setModule(logAnnotation.module())
                    .setOperation(logAnnotation.operation())
                    .setRequestMethod(request == null ? null : request.getMethod())
                    .setRequestUri(request == null ? null : request.getRequestURI())
                    .setClassMethod(signature.getDeclaringTypeName() + "." + signature.getName())
                    .setParams(buildParams(signature.getParameterNames(), joinPoint.getArgs()))
                    .setSuccess(resolveSuccess(result, thrown))
                    .setErrorMsg(truncate(resolveErrorMsg(result, thrown), ERROR_MAX_LENGTH))
                    .setCostTime(costTime)
                    .setIp(request == null ? null : resolveIp(request))
                    .setCreateTime(LocalDateTime.now());
            operationLogService.save(log);
        } catch (Exception ignored) {
            // Operation log failure must not affect admin business operations.
        }
    }

    private HttpServletRequest getCurrentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes)) {
            return null;
        }
        return ((ServletRequestAttributes) attributes).getRequest();
    }

    private String buildParams(String[] parameterNames, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (shouldSkipArg(arg)) {
                continue;
            }
            String name = parameterNames != null && i < parameterNames.length ? parameterNames[i] : "arg" + i;
            params.put(name, arg);
        }
        if (params.isEmpty()) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(params), PARAMS_MAX_LENGTH);
        } catch (JsonProcessingException e) {
            return truncate(params.toString(), PARAMS_MAX_LENGTH);
        }
    }

    private boolean shouldSkipArg(Object arg) {
        return arg instanceof ServletRequest
                || arg instanceof ServletResponse
                || arg instanceof MultipartFile
                || arg instanceof BindingResult;
    }

    private Integer resolveSuccess(Object result, Throwable thrown) {
        if (thrown != null) {
            return 0;
        }
        if (result instanceof Result) {
            Boolean success = ((Result) result).getSuccess();
            return Boolean.TRUE.equals(success) ? 1 : 0;
        }
        return 1;
    }

    private String resolveErrorMsg(Object result, Throwable thrown) {
        if (thrown != null) {
            return thrown.getMessage();
        }
        if (result instanceof Result) {
            Result apiResult = (Result) result;
            if (!Boolean.TRUE.equals(apiResult.getSuccess())) {
                return apiResult.getErrorMsg();
            }
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && ip.length() > 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && ip.length() > 0 && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
