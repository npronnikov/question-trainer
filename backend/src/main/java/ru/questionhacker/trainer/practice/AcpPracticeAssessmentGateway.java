package ru.questionhacker.trainer.practice;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ru.questionhacker.trainer.AcpGateway;
import ru.questionhacker.trainer.AppProperties;

@Component
public class AcpPracticeAssessmentGateway implements PracticeAssessmentGateway {

    private final AcpGateway acp;
    private final AppProperties properties;
    private final PracticePromptCatalog prompts;

    public AcpPracticeAssessmentGateway(AcpGateway acp, AppProperties properties,
                                        PracticePromptCatalog prompts) {
        this.acp = acp;
        this.properties = properties;
        this.prompts = prompts;
    }

    @Override
    public Result assess(Input input, String requestedModel) {
        String model = requestedModel == null || requestedModel.isBlank()
                ? properties.acp().defaultModel()
                : requestedModel.strip();
        if (model != null && !model.isBlank() && !properties.acp().models().contains(model)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Неизвестная модель: " + model);
        }
        return new Result(acp.ask(prompts.render(input), model, ignored -> { }), model);
    }
}
