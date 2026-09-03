package com.qilu.service.impl;

import com.qilu.entity.ServicePoint;
import com.qilu.mapper.ServicePointMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServicePointServiceImplTest {

    @Test
    void queryByCategoryRebuildsMissingGeoIndexFromMysql() {
        ServicePointMapper mapper = mock(ServicePointMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geoOperations = mock(GeoOperations.class);
        ServicePoint servicePoint = new ServicePoint()
                .setId(41L)
                .setCategoryId(7L)
                .setStatus(1)
                .setX(117.12)
                .setY(36.66);

        when(redisTemplate.hasKey("service:geo:7")).thenReturn(false);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(servicePoint));

        ServicePointServiceImpl service = new ServicePointServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        service.queryByCategory(7, 1, 117.12, 36.66);

        verify(geoOperations).add("service:geo:7", new Point(117.12, 36.66), "41");
    }

    @Test
    void queryByCategoryKeepsExistingGeoIndex() {
        ServicePointMapper mapper = mock(ServicePointMapper.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geoOperations = mock(GeoOperations.class);

        when(redisTemplate.hasKey("service:geo:7")).thenReturn(true);
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        ServicePointServiceImpl service = new ServicePointServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);

        service.queryByCategory(7, 1, 117.12, 36.66);

        verify(mapper, never()).selectList(any());
        verify(geoOperations, never()).add(any(), any(Point.class), any());
    }
}
