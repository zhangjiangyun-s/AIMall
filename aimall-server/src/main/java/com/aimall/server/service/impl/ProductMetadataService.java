package com.aimall.server.service.impl;

import com.aimall.server.entity.*;
import com.aimall.server.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductMetadataService {
    private final PmsBrandMapper brandMapper;
    private final PmsCategoryAttributeTemplateMapper templateMapper;
    private final PmsProductCategoryMapper categoryMapper;
    private final ObjectMapper objectMapper;

    public ProductMetadataService(PmsBrandMapper brandMapper,
                                  PmsCategoryAttributeTemplateMapper templateMapper,
                                  PmsProductCategoryMapper categoryMapper,
                                  ObjectMapper objectMapper) {
        this.brandMapper = brandMapper; this.templateMapper = templateMapper;
        this.categoryMapper = categoryMapper; this.objectMapper = objectMapper;
    }

    public List<PmsBrand> brands() {
        return brandMapper.selectList(new LambdaQueryWrapper<PmsBrand>()
                .orderByDesc(PmsBrand::getStatus).orderByDesc(PmsBrand::getSort).orderByAsc(PmsBrand::getId));
    }
    public PmsBrand saveBrand(PmsBrand brand) {
        if (brand.getName() == null || brand.getName().isBlank()) throw new IllegalArgumentException("品牌名称不能为空");
        brand.setName(brand.getName().trim());
        if (brand.getSort() == null) brand.setSort(0);
        if (brand.getStatus() == null) brand.setStatus(1);
        brand.setUpdateTime(LocalDateTime.now());
        if (brand.getId() == null) { brand.setCreateTime(LocalDateTime.now()); brandMapper.insert(brand); }
        else if (brandMapper.updateById(brand) != 1) throw new RuntimeException("品牌不存在");
        return brandMapper.selectById(brand.getId());
    }
    public List<PmsCategoryAttributeTemplate> templates(Long categoryId) {
        return templateMapper.selectList(new LambdaQueryWrapper<PmsCategoryAttributeTemplate>()
                .eq(categoryId != null, PmsCategoryAttributeTemplate::getCategoryId, categoryId)
                .orderByDesc(PmsCategoryAttributeTemplate::getStatus).orderByAsc(PmsCategoryAttributeTemplate::getId));
    }
    public PmsCategoryAttributeTemplate saveTemplate(PmsCategoryAttributeTemplate template) {
        if (template.getCategoryId() == null || categoryMapper.selectById(template.getCategoryId()) == null)
            throw new IllegalArgumentException("商品类目不存在");
        if (template.getTemplateName() == null || template.getTemplateName().isBlank())
            throw new IllegalArgumentException("属性模板名称不能为空");
        validateSchema(template.getSchemaJson());
        if (template.getStatus() == null) template.setStatus(1);
        template.setUpdateTime(LocalDateTime.now());
        if (template.getId() == null) { template.setCreateTime(LocalDateTime.now()); templateMapper.insert(template); }
        else if (templateMapper.updateById(template) != 1) throw new RuntimeException("属性模板不存在");
        return templateMapper.selectById(template.getId());
    }
    private void validateSchema(String json) {
        try {
            var root = objectMapper.readTree(json);
            if (!root.isArray()) throw new IllegalArgumentException("属性模板必须是 JSON 数组");
            for (var item : root) {
                if (!item.hasNonNull("name") || item.get("name").asText().isBlank())
                    throw new IllegalArgumentException("属性模板字段必须包含 name");
                if (item.has("values") && !item.get("values").isArray())
                    throw new IllegalArgumentException("属性模板 values 必须是数组");
            }
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("属性模板不是合法 JSON"); }
    }
}
