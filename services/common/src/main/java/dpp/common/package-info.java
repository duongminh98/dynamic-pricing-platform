/**
 * Cross-cutting common library for Dynamic Pricing Platform microservices.
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link dpp.common.api.CorrelationIdFilter} — X-Correlation-Id read/generate/MDC (R19.5)</li>
 *   <li>{@link dpp.common.api.CorrelationIdInterceptor} — Propagate correlation-id on outgoing calls (R19.6)</li>
 *   <li>{@link dpp.common.api.GlobalExceptionHandler} — Structured error responses (R19.3, R18.4)</li>
 *   <li>{@link dpp.common.api.ErrorCode} — Canonical error codes per design §7.2</li>
 *   <li>{@link dpp.common.api.ServiceException} — Business exception with ErrorCode</li>
 *   <li>{@link dpp.common.outbox.OutboxEntity} — Transactional outbox entity (R10.1, R10.5)</li>
 *   <li>{@link dpp.common.outbox.OutboxRelay} — Scheduled poller: outbox → RabbitMQ</li>
 *   <li>{@link dpp.common.outbox.OutboxPublisher} — Helper for enqueueing outbox entries</li>
 * </ul>
 *
 * <p>Auto-configured via {@link dpp.common.config.CommonAutoConfiguration}.</p>
 */
package dpp.common;
