package com.aimall.server.service.impl;
import com.aimall.server.entity.*; import com.aimall.server.mapper.*; import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service; import java.time.LocalDateTime; import java.util.*;
import com.aimall.server.exception.ResourceNotFoundException;
@Service public class ProductInteractionService {
 private final PmsProductReviewMapper reviews; private final UmsMemberProductFavoriteMapper favorites;
 private final UmsMemberBrowseHistoryMapper history; private final PmsProductMapper products;
 public ProductInteractionService(PmsProductReviewMapper r,UmsMemberProductFavoriteMapper f,UmsMemberBrowseHistoryMapper h,PmsProductMapper p){reviews=r;favorites=f;history=h;products=p;}
 public PmsProductReview review(Long m,Long p,Long item,int rating,String content){
  requireProduct(p); if(rating<1||rating>5)throw new IllegalArgumentException("评分必须在 1 到 5 之间");
  if(reviews.canReview(m,p,item)!=1)throw new RuntimeException("仅已完成订单的购买者可以评价");
  PmsProductReview r=new PmsProductReview();r.setMemberId(m);r.setProductId(p);r.setOrderItemId(item);r.setRating(rating);
  r.setContent(content==null?"":content.trim());r.setStatus(1);r.setCreateTime(LocalDateTime.now());
  try{reviews.insert(r);}catch(Exception e){throw new RuntimeException("该订单商品已经评价");} return r;
 }
 public List<PmsProductReview> reviews(Long p){return reviews.selectList(new LambdaQueryWrapper<PmsProductReview>().eq(PmsProductReview::getProductId,p).eq(PmsProductReview::getStatus,1).orderByDesc(PmsProductReview::getId).last("LIMIT 200"));}
 public void favorite(Long m,Long p){requireProduct(p);favorites.insertIgnore(m,p);}
 public void unfavorite(Long m,Long p){favorites.delete(new LambdaQueryWrapper<UmsMemberProductFavorite>().eq(UmsMemberProductFavorite::getMemberId,m).eq(UmsMemberProductFavorite::getProductId,p));}
 public List<UmsMemberProductFavorite> favorites(Long m){return favorites.selectList(new LambdaQueryWrapper<UmsMemberProductFavorite>().eq(UmsMemberProductFavorite::getMemberId,m).orderByDesc(UmsMemberProductFavorite::getId));}
 public void browse(Long m,Long p){requireProduct(p);history.record(m,p);}
 public List<UmsMemberBrowseHistory> history(Long m){return history.selectList(new LambdaQueryWrapper<UmsMemberBrowseHistory>().eq(UmsMemberBrowseHistory::getMemberId,m).orderByDesc(UmsMemberBrowseHistory::getLastViewTime).last("LIMIT 200"));}
 public List<Map<String,Object>> recommendations(Long m){
  var recent=history(m); Long category=null; if(!recent.isEmpty()){var p=products.selectById(recent.get(0).getProductId());if(p!=null)category=p.getCategoryId();}
  var q=new LambdaQueryWrapper<PmsProduct>().eq(PmsProduct::getDeleteStatus,0).eq(PmsProduct::getPublishStatus,1).eq(category!=null,PmsProduct::getCategoryId,category).orderByDesc(PmsProduct::getRecommandStatus).orderByDesc(PmsProduct::getSale).last("LIMIT 12");
  final Long c=category; return products.selectList(q).stream().map(p->{Map<String,Object>x=new LinkedHashMap<>();x.put("product",p);x.put("reason",c==null?"当前热销商品":"基于你最近浏览的同类商品");return x;}).toList();
 }
 private void requireProduct(Long id){var p=products.selectById(id);if(p==null||!Integer.valueOf(1).equals(p.getPublishStatus())||Integer.valueOf(1).equals(p.getDeleteStatus()))throw new ResourceNotFoundException("商品不存在");}
}
