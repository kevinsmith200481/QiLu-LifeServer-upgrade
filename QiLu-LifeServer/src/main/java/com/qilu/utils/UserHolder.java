package com.qilu.utils;

import com.qilu.dto.UserDTO;

public class UserHolder {
    //ThreadLocal 可以让同一线程内的所有代码共享用户信息，
    // 且不同线程之间互不干扰，非常适合处理 “用户登录后，后续请求如何携带用户身份” 的场景。
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
