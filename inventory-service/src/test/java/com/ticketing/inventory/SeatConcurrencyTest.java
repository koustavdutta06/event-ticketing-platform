package com.ticketing.inventory;

import com.ticketing.inventory.entities.Seat;
import com.ticketing.inventory.enums.SeatStatus;
import com.ticketing.inventory.repository.SeatRepository;
import com.ticketing.inventory.service.SeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

//@SpringBootTest
class SeatConcurrencyTest {

    @Autowired
    private SeatService seatService;

    @Autowired
    private SeatRepository seatRepository;

    private Long seatId;

    private static final Long TEST_SEAT_ID = 2L;

//    @BeforeEach
//    void setUp() {
//        Seat seat = Seat.builder()
//                .eventId(4L)
//                .seatNumber("A15")
//                .seatSection("Balcony")
//                .price(new BigDecimal(1500.00))
//                .status(SeatStatus.AVAILABLE)
//                .build();
//        seatId = seatRepository.save(seat).getId();
//    }

//    @BeforeEach
    void setUp() {
        Seat seat = seatRepository.findById(TEST_SEAT_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "Test seat id " + TEST_SEAT_ID + " not found — insert it first or change TEST_SEAT_ID"));

        seat.setStatus(SeatStatus.AVAILABLE);
        seatRepository.save(seat);
        seatId = seat.getId();
    }

//    @Test
    void onlyOneConcurrentHoldShouldSucceed() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await(); // all threads block here until released together
                    var result = seatService.holdSeat(seatId, 5L);
                    if (result.success()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // ObjectOptimisticLockingFailureException lands here for genuine race losers
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();          // wait until all 10 threads are ready and blocked
        startLatch.countDown();      // release them all at once — this is the actual concurrency trigger
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);

        Seat finalSeat = seatRepository.findById(seatId).orElseThrow();
        assertThat(finalSeat.getStatus()).isEqualTo(SeatStatus.HELD);
    }
}