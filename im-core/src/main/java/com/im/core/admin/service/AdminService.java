package com.im.core.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.exception.BizException;
import com.im.core.admin.entity.Admin;
import com.im.core.admin.mapper.AdminMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AdminMapper adminMapper;

    public Admin findByUsername(String username) {
        return adminMapper.selectOne(
                new LambdaQueryWrapper<Admin>().eq(Admin::getUsername, username));
    }

    public Admin findById(Long id) {
        return adminMapper.selectById(id);
    }

    public List<Admin> list() {
        return adminMapper.selectList(
                new LambdaQueryWrapper<Admin>().orderByDesc(Admin::getRole).orderByAsc(Admin::getId));
    }

    public Admin create(String username, String encodedPassword, int role, Long createdBy) {
        if (findByUsername(username) != null) {
            throw new BizException(400, "管理员账号已存在");
        }
        Admin admin = new Admin();
        admin.setUsername(username);
        admin.setPassword(encodedPassword);
        admin.setRole(role);
        admin.setStatus(1);
        admin.setMustChangePassword(1);
        admin.setCreatedBy(createdBy);
        adminMapper.insert(admin);
        return admin;
    }

    public void delete(Long id) {
        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BizException(404, "管理员不存在");
        }
        if (admin.getRole() != null && admin.getRole() == 0) {
            throw new BizException(403, "不能删除超级管理员");
        }
        adminMapper.deleteById(id);
    }

    public void updatePassword(Long id, String encodedPassword) {
        Admin admin = adminMapper.selectById(id);
        if (admin == null) {
            throw new BizException(404, "管理员不存在");
        }
        admin.setPassword(encodedPassword);
        admin.setMustChangePassword(0);
        adminMapper.updateById(admin);
    }
}
