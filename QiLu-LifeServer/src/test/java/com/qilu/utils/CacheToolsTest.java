package com.qilu.utils;

import com.qilu.entity.ServicePoint;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheToolsTest {

    @Test
    void detailCacheUsesPrefixAndIdForReadAndWrite() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("cache:service-point:41")).thenReturn(null);
        ServicePoint point = new ServicePoint().setId(41L).setName("Library Printing Point");
        @SuppressWarnings("unchecked")
        Function<Long, ServicePoint> loader = mock(Function.class);
        when(loader.apply(41L)).thenReturn(point);

        Cache_tools cacheTools = new Cache_tools();
        ReflectionTestUtils.setField(cacheTools, "stringRedisTemplate", redisTemplate);

        ServicePoint result = cacheTools.queryWithPassThoughtTools(
                "cache:service-point:", 41L, ServicePoint.class, loader, 30L, TimeUnit.MINUTES);

        assertEquals(41L, result.getId());
        verify(values).get("cache:service-point:41");
        verify(values).set(org.mockito.ArgumentMatchers.eq("cache:service-point:41"),
                anyString(), org.mockito.ArgumentMatchers.eq(30L), org.mockito.ArgumentMatchers.eq(TimeUnit.MINUTES));
        verify(values, never()).get("cache:service-point:");
    }

    @Test
    void missingDetailCachesTheEmptyValueUnderTheIdKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("cache:service-point:99")).thenReturn(null);
        @SuppressWarnings("unchecked")
        Function<Long, ServicePoint> loader = mock(Function.class);
        when(loader.apply(99L)).thenReturn(null);

        Cache_tools cacheTools = new Cache_tools();
        ReflectionTestUtils.setField(cacheTools, "stringRedisTemplate", redisTemplate);
        cacheTools.queryWithPassThoughtTools(
                "cache:service-point:", 99L, ServicePoint.class, loader, 2L, TimeUnit.MINUTES);

        verify(values).set("cache:service-point:99", "", 2L, TimeUnit.MINUTES);
    }
}
