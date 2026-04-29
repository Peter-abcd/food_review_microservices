package com.hmdp.voucher.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    @Select("SELECT v.*, s.stock, s.begin_time, s.end_time " +
            "FROM tb_voucher v LEFT JOIN tb_seckill_voucher s ON v.id = s.voucher_id " +
            "WHERE v.shop_id = #{shopId} AND v.status = 1")
    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}