package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AcpInteractionLoggerTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private AcpInteractionLogger subject;
    private boolean additive;

    @BeforeEach
    void captureEvents() {
        logger = (Logger) LoggerFactory.getLogger(AcpInteractionLogger.LOGGER_NAME);
        additive = logger.isAdditive();
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        subject = new AcpInteractionLogger();
    }

    @AfterEach
    void stopCapture() {
        logger.detachAppender(appender);
        logger.setAdditive(additive);
        appender.stop();
    }

    @Test
    void requestAndResponseShareInteractionIdAndKeepFullPayloads() {
        var interaction = subject.begin("full\nprompt", "gpt-test");

        subject.success(interaction, "full\nanswer");

        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(0).getFormattedMessage()).contains(
                "ACP request", interaction.id().toString(), "gpt-test", "full\nprompt");
        assertThat(events.get(1).getLevel()).isEqualTo(Level.INFO);
        assertThat(events.get(1).getFormattedMessage()).contains(
                "ACP response", interaction.id().toString(), "full\nanswer", "durationMs=");
    }

    @Test
    void failureIncludesCorrelationAndStackTrace() {
        var interaction = subject.begin("prompt", "gpt-test");

        subject.failure(interaction, new IllegalStateException("agent failed"));

        ILoggingEvent failure = appender.list.getLast();
        assertThat(failure.getLevel()).isEqualTo(Level.ERROR);
        assertThat(failure.getFormattedMessage()).contains(
                "ACP error", interaction.id().toString(), "durationMs=");
        assertThat(failure.getThrowableProxy()).isNotNull();
        assertThat(failure.getThrowableProxy().getMessage()).isEqualTo("agent failed");
    }
}
