package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.AppointmentOrder;
import com.qilu.entity.AppointmentSlot;
import com.qilu.entity.ServicePoint;
import com.qilu.entity.ServiceTicket;
import com.qilu.mapper.AppointmentOrderMapper;
import com.qilu.mapper.AppointmentSlotMapper;
import com.qilu.mapper.ServicePointMapper;
import com.qilu.mapper.ServiceTicketMapper;
import com.qilu.mapper.StationCommentMapper;
import com.qilu.service.IServicePointService;
import com.qilu.utils.Cache_tools;
import com.qilu.utils.SystemConstants;
import com.qilu.utils.UserHolder;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.qilu.utils.RedisConstants.CACHE_SERVICE_POINT_KEY;
import static com.qilu.utils.RedisConstants.SERVICE_POINT_GEO_KEY;

@Service
public class ServicePointServiceImpl extends ServiceImpl<ServicePointMapper, ServicePoint> implements IServicePointService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private Cache_tools cacheTools;

    @Resource
    private AppointmentSlotMapper appointmentSlotMapper;

    @Resource
    private AppointmentOrderMapper appointmentOrderMapper;

    @Resource
    private ServiceTicketMapper serviceTicketMapper;

    @Resource
    private StationCommentMapper stationCommentMapper;

    @Override
    public Result queryById(Long id) {
        ServicePoint servicePoint = cacheTools.queryWithPassThoughtTools(
                CACHE_SERVICE_POINT_KEY, id, ServicePoint.class, this::getById, 30L, TimeUnit.MINUTES);
        if (servicePoint == null) {
            return Result.fail("service point not found");
        }
        fillCommentCounts(Collections.singletonList(servicePoint));
        return Result.ok(servicePoint);
    }

    @Override
    @Transactional
    public Result saveServicePoint(ServicePoint servicePoint) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        if (isManager(user)) {
            servicePoint.setManagerId(user.getId());
        } else if (!isAdmin(user)) {
            return Result.fail("No permission to create service point");
        }
        save(servicePoint);
        return Result.ok(servicePoint.getId());
    }

    @Override
    @Transactional
    public Result updateServicePoint(ServicePoint servicePoint) {
        if (servicePoint.getId() == null) {
            return Result.fail("service point id is required");
        }
        UserDTO user = UserHolder.getUser();
        if (!canManageServicePoint(user, servicePoint.getId())) {
            return Result.fail("No permission to manage this service point");
        }
        if (isManager(user)) {
            servicePoint.setManagerId(user.getId());
            servicePoint.setStatus(2);
        }
        updateById(servicePoint);
        stringRedisTemplate.delete(CACHE_SERVICE_POINT_KEY + servicePoint.getId());
        return Result.ok();
    }

    @Override
    public Result queryByCategory(Integer categoryId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<ServicePoint> page = query()
                    .eq("category_id", categoryId)
                    .eq("status", 1)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            fillCommentCounts(page.getRecords());
            return Result.ok(page.getRecords(), page.getTotal());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = SERVICE_POINT_GEO_KEY + categoryId;
        rebuildGeoIndexIfMissing(categoryId, key);
        GeoResults<RedisGeoCommands.GeoLocation<String>> search =
                stringRedisTemplate.opsForGeo().search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(50000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if (search == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = search.getContent();
        if (content.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<String> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(item -> {
            String servicePointId = item.getContent().getName();
            ids.add(servicePointId);
            distanceMap.put(servicePointId, item.getDistance());
        });
        String idStr = StrUtil.join(",", ids);
        List<ServicePoint> servicePoints = query().in("id", ids).eq("status", 1).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (ServicePoint servicePoint : servicePoints) {
            servicePoint.setDistance(distanceMap.get(servicePoint.getId().toString()).getValue());
        }
        fillCommentCounts(servicePoints);
        return Result.ok(servicePoints);
    }

    private void rebuildGeoIndexIfMissing(Integer categoryId, String key) {
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }
        List<ServicePoint> servicePoints = query()
                .eq("category_id", categoryId)
                .eq("status", 1)
                .isNotNull("x")
                .isNotNull("y")
                .list();
        for (ServicePoint servicePoint : servicePoints) {
            stringRedisTemplate.opsForGeo().add(
                    key,
                    new Point(servicePoint.getX(), servicePoint.getY()),
                    servicePoint.getId().toString()
            );
        }
    }

    @Override
    public Result queryByName(String name, Integer current) {
        Page<ServicePoint> page = query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .eq("status", 1)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        fillCommentCounts(page.getRecords());
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    public Result queryEnabledPoints() {
        List<ServicePoint> servicePoints = query()
                .eq("status", 1)
                .orderByAsc("id")
                .list();
        fillCommentCounts(servicePoints);
        return Result.ok(servicePoints);
    }

    @Override
    public Result queryAdminPage(Integer current, Integer status, String name) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("Please login first");
        }
        if (!isAdmin(user) && !isManager(user)) {
            return Result.fail("No permission to query service points");
        }
        Page<ServicePoint> page = query()
                .eq(isManager(user), "manager_id", user.getId())
                .eq(status != null, "status", status)
                .like(StrUtil.isNotBlank(name), "name", name)
                .orderByDesc("update_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional
    public Result approveServicePoint(Long id) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("Only admin can approve service point");
        }
        return updateServicePointStatus(id, 1);
    }

    @Override
    @Transactional
    public Result enableServicePoint(Long id) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("Only admin can enable service point");
        }
        ServicePoint servicePoint = getById(id);
        if (servicePoint == null) {
            return Result.fail("service point not found");
        }
        if (Integer.valueOf(2).equals(servicePoint.getStatus())) {
            return Result.fail("Pending service point must be approved before enable");
        }
        return updateServicePointStatus(id, 1);
    }

    @Override
    @Transactional
    public Result disableServicePoint(Long id) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("Only admin can disable service point");
        }
        return updateServicePointStatus(id, 0);
    }

    @Override
    @Transactional
    public Result deleteServicePoint(Long id) {
        UserDTO user = UserHolder.getUser();
        if (!isAdmin(user)) {
            return Result.fail("Only admin can delete service point");
        }
        ServicePoint servicePoint = getById(id);
        if (servicePoint == null) {
            return Result.fail("service point not found");
        }
        if (!Integer.valueOf(0).equals(servicePoint.getStatus())) {
            return Result.fail("Please disable service point before deletion");
        }
        if (hasAppointmentSlots(id)) {
            return Result.fail("Cannot delete service point with appointment slots");
        }
        if (hasAppointmentOrders(id)) {
            return Result.fail("Cannot delete service point with appointment orders");
        }
        if (hasServiceTickets(id)) {
            return Result.fail("Cannot delete service point with service tickets");
        }
        removeById(id);
        stringRedisTemplate.delete(CACHE_SERVICE_POINT_KEY + id);
        if (servicePoint.getCategoryId() != null) {
            stringRedisTemplate.opsForZSet().remove(SERVICE_POINT_GEO_KEY + servicePoint.getCategoryId(), id.toString());
        }
        return Result.ok();
    }

    private Result updateServicePointStatus(Long id, Integer status) {
        UserDTO user = UserHolder.getUser();
        if (!canManageServicePoint(user, id)) {
            return Result.fail("No permission to manage this service point");
        }
        ServicePoint oldPoint = getById(id);
        if (oldPoint == null) {
            return Result.fail("service point not found");
        }
        if (status.equals(oldPoint.getStatus())) {
            stringRedisTemplate.delete(CACHE_SERVICE_POINT_KEY + id);
            return Result.ok();
        }
        ServicePoint patch = new ServicePoint();
        patch.setId(id);
        patch.setStatus(status);
        updateById(patch);
        stringRedisTemplate.delete(CACHE_SERVICE_POINT_KEY + id);
        return Result.ok();
    }

    private boolean canManageServicePoint(UserDTO user, Long servicePointId) {
        if (user == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (!isManager(user)) {
            return false;
        }
        ServicePoint servicePoint = getById(servicePointId);
        return servicePoint != null && user.getId().equals(servicePoint.getManagerId());
    }

    private boolean hasAppointmentSlots(Long servicePointId) {
        Long count = appointmentSlotMapper.selectCount(new LambdaQueryWrapper<AppointmentSlot>()
                .eq(AppointmentSlot::getServicePointId, servicePointId));
        return count != null && count > 0;
    }

    private boolean hasAppointmentOrders(Long servicePointId) {
        Long count = appointmentOrderMapper.selectCount(new LambdaQueryWrapper<AppointmentOrder>()
                .eq(AppointmentOrder::getServicePointId, servicePointId));
        return count != null && count > 0;
    }

    private boolean hasServiceTickets(Long servicePointId) {
        Long count = serviceTicketMapper.selectCount(new LambdaQueryWrapper<ServiceTicket>()
                .eq(ServiceTicket::getServicePointId, servicePointId));
        return count != null && count > 0;
    }

    private void fillCommentCounts(List<ServicePoint> servicePoints) {
        if (servicePoints == null || servicePoints.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>(servicePoints.size());
        for (ServicePoint servicePoint : servicePoints) {
            if (servicePoint.getId() != null) {
                ids.add(servicePoint.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Integer> countMap = new HashMap<>(ids.size());
        for (Map<String, Object> row : stationCommentMapper.selectCommentCountsByStationIds(ids)) {
            Object stationId = row.get("stationId");
            Object commentCount = row.get("commentCount");
            if (stationId == null) {
                stationId = row.get("station_id");
            }
            if (commentCount == null) {
                commentCount = row.get("comment_count");
            }
            if (stationId instanceof Number && commentCount instanceof Number) {
                countMap.put(((Number) stationId).longValue(), ((Number) commentCount).intValue());
            }
        }
        for (ServicePoint servicePoint : servicePoints) {
            servicePoint.setCommentCount(countMap.getOrDefault(servicePoint.getId(), 0));
        }
    }

    private boolean isAdmin(UserDTO user) {
        return user != null && "admin".equals(user.getRole());
    }

    private boolean isManager(UserDTO user) {
        return user != null && "manager".equals(user.getRole());
    }
}
