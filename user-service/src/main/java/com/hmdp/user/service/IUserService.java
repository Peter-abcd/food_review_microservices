package com.hmdp.user.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import jakarta.servlet.http.HttpSession;

import java.util.List;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 根据 id 集合批量查询用户（返回 List&lt;UserDTO&gt;，供 social-service 的 Feign 调用）
     */
    Result queryUserByIds(List<Long> ids);

    Result sign();

    Result signCount();
}