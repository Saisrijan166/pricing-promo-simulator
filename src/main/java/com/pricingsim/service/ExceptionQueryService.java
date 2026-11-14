package com.pricingsim.service;

import java.time.LocalDate;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.pricingsim.api.dto.ExceptionView;
import com.pricingsim.api.dto.PageResponse;
import com.pricingsim.domain.enums.ExceptionCategory;
import com.pricingsim.domain.enums.IssueType;
import com.pricingsim.persistence.ExceptionDao;

/** The exception queue: detector findings filtered and paged, sorted by event date then exception ID. */
@Service
@Profile("!offline")
public class ExceptionQueryService {

    private final ExceptionDao exceptions;

    public ExceptionQueryService(ExceptionDao exceptions) {
        this.exceptions = exceptions;
    }

    public PageResponse<ExceptionView> query(ExceptionCategory category, IssueType issueType, String skuId,
                                             LocalDate from, LocalDate to, int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        ExceptionDao.Filter f = new ExceptionDao.Filter(category == null ? null : category.name(),
                issueType == null ? null : issueType.name(), skuId, from, to);
        long total = exceptions.count(f);
        return PageResponse.of(exceptions.page(f, page, size).stream().map(ExceptionView::of).toList(), page, size,
                total);
    }
}
