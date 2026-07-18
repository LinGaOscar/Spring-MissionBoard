package com.wbsflow.wbs;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WbsNodeRepository extends JpaRepository<WbsNode, Long> {
    List<WbsNode> findByParentId(Long parentId);
}
