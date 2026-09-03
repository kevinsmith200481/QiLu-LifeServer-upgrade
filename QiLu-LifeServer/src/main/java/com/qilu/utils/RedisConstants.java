package com.qilu.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final String CACHE_SERVICE_POINT_KEY = "cache:service-point:";

    public static final String APPOINTMENT_QUOTA_KEY = "appointment:quota:";
    public static final String APPOINTMENT_ORDER_KEY = "appointment:order:";
    public static final String APPOINTMENT_CANCEL_RELEASE_KEY = "appointment:cancel:released:";
    public static final String APPOINTMENT_REMINDER_KEY = "appointment:reminder:30m:";
    public static final String APPOINTMENT_ORDER_STREAM_KEY = "stream.appointment-orders";
    public static final String SERVICE_POINT_GEO_KEY = "service:geo:";

    public static final String CACHE_LIST_KEY = "cache:list:";
    public static final String CACHE_SERVICE_CATEGORY_LIST_KEY = "cache:service-category:list";

    public static final String INBOX_UNREAD_HASH_KEY = "inbox:unread:";
    public static final String INBOX_READ_BITMAP_KEY = "inbox:read:";
    public static final String INBOX_HOT_MESSAGE_KEY = "inbox:hot:";
    public static final String INBOX_WS_CHANNEL = "inbox:ws:push";
    public static final String INBOX_CONSUME_DEDUP_KEY = "inbox:consume:";
    public static final Long INBOX_HOT_MESSAGE_TTL = 60L;
}
