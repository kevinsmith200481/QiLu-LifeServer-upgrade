package com.qilu.utils;

public interface ILOCK {
    //尝试获取锁
    boolean tryGetLock(Long timeout);
    //释放锁
    void releaseLock();
}
