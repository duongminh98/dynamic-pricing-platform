package dpp.product.repository;

import dpp.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, String> {

    List<Product> findByActiveTrue();

    List<Product> findByCategoryAndActiveTrue(String category);

    Optional<Product> findByProductIdAndActiveTrue(String productId);
}


