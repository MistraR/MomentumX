package com.mistra.plank.dao;

import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mistra.plank.model.entity.Momentum;

/**
 * @ author: rui.wang@yamu.com
 * @ description:
 * @ date: 2024/8/29
 */
@Mapper
public interface MomentumDao extends BaseMapper<Momentum> {
}
