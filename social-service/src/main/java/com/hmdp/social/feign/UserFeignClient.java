package com.hmdp.social.feign;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 用户服务Feign客户端
 */
@FeignClient(name = "user-service")
public interface UserFeignClient {

    /**
     * 根据用户id查询用户信息
     */
    @GetMapping("/user/{id}")
    Result getUserById(@PathVariable("id") Long id);

    /**
     * 根据用户id列表查询用户信息
     *
     * 【必须是 POST】批量 id 放在请求体里。原先是 @GetMapping + @RequestBody：
     *   ① GET 带 body 是反模式，网关/代理可能直接丢掉 body；
     *   ② user-service 里原本没有 /user/list 这个 handler，请求会落到 /user/{id} 上，
     *      把 "list" 当 Long 解析而失败。
     * 配套：user-service 的 UserController#queryUserByIds 新增了 @PostMapping("/list")。
     */
    @PostMapping("/user/list")
    Result getUserByIds(@RequestBody List<Long> ids);
}
