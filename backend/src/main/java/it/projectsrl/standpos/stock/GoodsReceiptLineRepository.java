package it.projectsrl.standpos.stock;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, UUID> {

    List<GoodsReceiptLine> findAllByGoodsReceiptIdOrderByLineNo(UUID goodsReceiptId);

    boolean existsByProductId(UUID productId);

    @Modifying
    @Transactional
    @Query("delete from GoodsReceiptLine l where l.goodsReceiptId = :goodsReceiptId")
    void deleteAllOfReceipt(UUID goodsReceiptId);
}
