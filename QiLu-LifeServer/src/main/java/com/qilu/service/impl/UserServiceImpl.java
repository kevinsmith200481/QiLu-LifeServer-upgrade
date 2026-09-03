package com.qilu.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qilu.dto.LoginFormDTO;
import com.qilu.dto.Result;
import com.qilu.dto.UserDTO;
import com.qilu.entity.User;
import com.qilu.mapper.UserMapper;
import com.qilu.service.IUserService;
import com.qilu.service.sms.SmsCodeSender;
import com.qilu.service.sms.SmsSendResult;
import com.qilu.utils.PasswordEncoder;
import com.qilu.utils.RegexPatterns;
import com.qilu.utils.RegexUtils;
import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import com.qilu.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.qilu.utils.RedisConstants.*;



@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource

    @Resource
    private SmsCodeSender smsCodeSender;

    //瀹炵幇鍙戦€佹墜鏈洪獙璇佺爜
    @Override
    public Result sendcode(String phone, HttpSession session) {
        //1.鎻愪氦鎵嬫満鍙?
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.鏍￠獙鎵嬫満鍙蜂笉绗﹀悎杩斿洖閿欒淇℃伅
            return Result.fail("your phone is not valid");
        }
        //3.绗﹀悎鐢熸垚楠岃瘉鐮?
        String code = RandomUtil.randomNumbers(6);
        //4.淇濆瓨楠岃瘉鐮佸苟鍙戦€?
        //session.setAttribute("code", code);
        //淇濆瓨鍒皉edis涓?
        SmsSendResult sendResult = smsCodeSender.sendCode(phone, code);
        if (!sendResult.isSuccess()) {
            return Result.fail(sendResult.getMessage());
        }
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //妯℃嫙鍙戦€佺煭淇?
        return Result.ok();
    }

    //瀹炵幇鍙戦€佹墜鏈洪獙璇佺爜
    @Override
    public Result login_in(LoginFormDTO loginForm, HttpSession session) {
        //1.鏍￠獙鎵嬫満鍙?
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.鏍￠獙鎵嬫満鍙蜂笉绗﹀悎杩斿洖閿欒淇℃伅
            return Result.fail("your phone is not valid");
        }
        //3.鏍￠獙楠岃瘉鐮?涓嶅瓨鍦ㄨ繑鍥為敊璇俊鎭?
        if (StrUtil.isNotBlank(loginForm.getPassword())) {
            return loginByPassword(phone, loginForm.getPassword());
        }
        return loginByCode(phone, loginForm.getCode());
        /*
        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String loginFormCode = loginForm.getCode();

        if(code==null||!code.equals(loginFormCode)){
            return Result.fail("your code is not valid");
        }
        //4.瀛樺湪鍏堟煡璇㈢敤鎴锋槸鍚﹀瓨鍦紝瀛樺湪鏍规嵁鎵嬫満鍙锋煡璇?
        //璇ヨ鍙ユ槸mybatis-plus鐨勬柟娉曪紝绾︾瓑浜巗elect * from tb_user where phone= ?
        User user = query().eq("phone", phone).one();

        if(user==null){
            //涓嶅瓨鍦紝鍒涘缓鏂扮敤鎴峰苟淇濆瓨
            user=createUserByPhone(phone);
        }
        //5.淇濆瓨鐢ㄦ埛淇℃伅鍒皉edis涓?
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //return Result.ok();
        //鐢熸垚token
        String token = UUID.randomUUID().toString(true);
        //灏唘ser瀵硅薄杞负hashmap瀛樺偍
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> usermap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //瀛樺偍
        String tokenkey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenkey, usermap);
        //璁剧疆token鏈夋晥鏈?
        stringRedisTemplate.expire(tokenkey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);*/
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isBlank(token)) {
            return Result.ok();
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        UserHolder.removeUser();
        return Result.ok();
    }

    private Result loginByCode(String phone, String loginFormCode) {
        if (RegexUtils.isCodeInvalid(loginFormCode)) {
            return Result.fail("your code is not valid");
        }

        String code = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (code == null || !code.equals(loginFormCode)) {
            return Result.fail("your code is not valid");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserByPhone(phone);
        }
        return issueToken(user);
    }

    private Result loginByPassword(String phone, String rawPassword) {
        if (!rawPassword.matches(RegexPatterns.PASSWORD_REGEX)) {
            return Result.fail("password must be 4 to 32 letters, numbers or underscores");
        }

        User user = query().eq("phone", phone).one();
        if (user == null) {
            return Result.fail("phone or password is incorrect");
        }
        if (StrUtil.isBlank(user.getPassword())) {
            return Result.fail("phone or password is incorrect");
        }
        if (!PasswordEncoder.matches(user.getPassword(), rawPassword)) {
            return Result.fail("phone or password is incorrect");
        }
        return issueToken(user);
    }

    private Result issueToken(User user) {
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }


    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_" + RandomUtil.randomNumbers(6));
        user.setRole("student");
        //淇濆瓨鐢ㄦ埛,save(user) 鏂规硶鐨勪綔鐢ㄦ槸灏嗘瀯寤哄ソ鐨?user 瀵硅薄鎸佷箙鍖栧埌鏁版嵁搴撲腑锛屽畬鎴愮敤鎴锋暟鎹殑瀛樺偍銆?
        save(user);
        return user;
    }
}
