package com.milano.quotation.purchase;
import jakarta.persistence.*;import java.util.UUID;
@Entity @Table(name="purchase_product_image")public class PurchaseProductImage{@Id public UUID id;@Column(name="product_id",nullable=false)public UUID productId;@Column(name="asset_id",nullable=false)public UUID assetId;@Column(name="image_type",nullable=false,length=24)public String imageType;@Column(name="sort_order",nullable=false)public int sortOrder;protected PurchaseProductImage(){}}
