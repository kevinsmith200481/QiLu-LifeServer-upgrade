package com.qilu.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qilu.dto.LoginFormDTO;
import com.qilu.dto.Result;
import com.qilu.entity.User;

import javax.servlet.http.HttpSession;



public interface IUserService extends IService<User> {

    Result sendcode(String phone, HttpSession session);

    Result login_in(LoginFormDTO loginForm, HttpSession session);

    Result logout(String token);

}
