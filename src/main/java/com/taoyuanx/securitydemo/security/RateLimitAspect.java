package com.taoyuanx.securitydemo.security;


import com.taoyuanx.securitydemo.exception.LimitException;
import com.taoyuanx.securitydemo.security.ratelimit.AbstractRateLimiter;
import com.taoyuanx.securitydemo.utils.HelperUtil;
import com.taoyuanx.securitydemo.utils.RequestUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Objects;

/**
 * aop限流
 */
@Aspect
@Component
public class RateLimitAspect {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimitAspect.class);

    @Autowired
    AbstractRateLimiter rateLimiter;
    /**
     * el表达式解析
     */
    private static ExpressionParser EL_PARSER = new SpelExpressionParser();

    @Pointcut("execution(* com.taoyuanx.securitydemo.controller..*.*(..))&& (@annotation(RateLimit)||@annotation(Rate))")
    public void ratePointCut() {
    }

    @Around("ratePointCut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        handleRateLimit(joinPoint);
        return joinPoint.proceed();
    }

    private void handleRateLimit(ProceedingJoinPoint joinPoint) throws Throwable {
        Signature signature = joinPoint.getSignature();
        MethodSignature methodSignature = (MethodSignature) signature;
        Method currentMethod = methodSignature.getMethod();
        String className = joinPoint.getTarget().getClass().getName();
        String fullMethodName = className + "." + methodSignature.getName();
        RateLimit rateLimit = AnnotationUtils.findAnnotation(currentMethod, RateLimit.class);
        if (rateLimit != null) {
            doHandleRateLimit(rateLimit, fullMethodName, joinPoint, methodSignature);
        } else {
            Rate rate = AnnotationUtils.findAnnotation(currentMethod, Rate.class);
            if (rate != null && rate.rate() != null) {
                for (RateLimit limit : rate.rate()) {
                    doHandleRateLimit(limit, fullMethodName, joinPoint, methodSignature);
                }
            }
        }
    }


    private void doHandleRateLimit(RateLimit rateLimit, String fullMethodName, ProceedingJoinPoint joinPoint, MethodSignature methodSignature) throws Throwable {
        RateLimitType type = rateLimit.type();
        String limitKey = parseLimitKey(rateLimit, fullMethodName, joinPoint, methodSignature);
        if (LOG.isDebugEnabled()) {
            LOG.debug("采用[{}]限流策略,限流key:{}", type, limitKey);
        }
        if (type.equals(RateLimitType.TOTAL_COUNT)) {
            if (!rateLimiter.tryCount(1, limitKey, rateLimit.totalCount())) {
                throw new LimitException("访问次数已达最大限制:" + rateLimit.totalCount() + ",请稍后再试");
            }
        } else {
            if (!rateLimiter.tryAcquire(limitKey, rateLimit.limit())) {
                throw new LimitException("请求过于频繁,请稍后再试");
            }
        }
    }


    private static final String GLOBAL_LIMIT_KEY = "global";

    private String parseLimitKey(RateLimit rateLimit, String fullMethodName, ProceedingJoinPoint joinPoint, MethodSignature methodSignature) {
        RateLimitType type = rateLimit.type();
        String limitKey = rateLimit.limitKey();
        boolean emptyLimitKey = HelperUtil.isEmpty(limitKey);
        switch (type) {
            case IP:
                if (emptyLimitKey) {
                    /**
                     * 单个方法ip限流
                     */
                    return RequestUtil.getRemoteIp() + "_" + fullMethodName;
                }
                /**
                 * 全局ip限流
                 */
                return RequestUtil.getRemoteIp() + "_" + limitKey;
            case METHOD:
                return fullMethodName;
            case SERVICE_KEY:
                /**
                 * el表达式只有在SERVICE_KEY时才会生效
                 * 允许用户自定义限流key
                 */
                limitKey = parseLimitKey(rateLimit.limitKey(), fullMethodName, joinPoint, methodSignature);
                if (HelperUtil.isEmpty(limitKey)) {
                    throw new LimitException("请指定limitKey");
                }
                return limitKey;
            case GLOBAL:
                return GLOBAL_LIMIT_KEY;
            default:
                return fullMethodName;
        }

    }

    private boolean isEl(String limitKey) {
        return limitKey.startsWith("#");
    }

    private String parseLimitKey(String limitKey, String fullMethodName, ProceedingJoinPoint joinPoint, MethodSignature methodSignature) {
        if (HelperUtil.isEmpty(limitKey)) {
            return limitKey;
        }
        if (!isEl(limitKey)) {
            return limitKey;
        }

        /**
         * el表达式解析
         * 并设置内置变量 方法参数
         */
        Object[] args = joinPoint.getArgs();
        String[] argsName = methodSignature.getParameterNames();
        EvaluationContext context = new StandardEvaluationContext();
        context.setVariable("fullMethodName", fullMethodName);
        if (Objects.nonNull(args) && args.length > 0) {
            context.setVariable("args", args);
            for (int i = 0; i < args.length; i++) {
                context.setVariable(argsName[i], args[i]);
            }
        }
        Expression expression = EL_PARSER.parseExpression(limitKey);
        return expression.getValue(context, String.class);

    }


}
