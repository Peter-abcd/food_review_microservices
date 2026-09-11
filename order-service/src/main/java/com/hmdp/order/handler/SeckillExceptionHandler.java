package com.hmdp.order.handler;

import com.hmdp.dto.Result;
import com.hmdp.exception.SeckillException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.ConnectException;

@RestControllerAdvice
@Slf4j
public class SeckillExceptionHandler {

    @ExceptionHandler(SeckillException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result handleSeckillException(SeckillException e) {
        log.error("绉掓潃涓氬姟寮傚父: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(RedisConnectionFailureException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleRedisConnectionFailure(RedisConnectionFailureException e) {
        log.error("Redis杩炴帴澶辫触锛岃Е鍙戦檷绾? {}", e.getMessage());
        return Result.fail("绯荤粺绻佸繖锛岃绋嶅悗閲嶈瘯");
    }

    @ExceptionHandler(ConnectException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result handleConnectException(ConnectException e) {
        log.error("缃戠粶杩炴帴寮傚父: {}", e.getMessage());
        return Result.fail("缃戠粶寮傚父锛岃绋嶅悗閲嶈瘯");
    }


    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result handleGenericException(Exception e) {
        log.error("绯荤粺寮傚父: ", e);
        return Result.fail("绯荤粺寮傚父锛岃绋嶅悗閲嶈瘯");
    }
}
