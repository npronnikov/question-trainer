# Theory Worked Examples Design

## Goal

Turn each theory chapter from a definition plus isolated question templates into a worked lesson that demonstrates the complete reasoning path of the technique. Preserve and expose the already researched question templates, exercises, field experiments, historical cases, sources, and interpretation limits that are currently discarded during curriculum generation.

## Approved product direction

The worked example is visible directly in the chapter after the formula. It is not hidden behind a disclosure and it is not a multi-step wizard. The learner should be able to scan the whole transformation from an ordinary question to a concrete experiment without interaction.

The chapter order is:

1. Definition, operation, mechanism, and formula.
2. One complete fictional worked example.
3. Two short question templates.
4. Fifteen-minute exercise and 24–48-hour field experiment.
5. Anti-pattern and control signal.
6. Expandable historical cases with sources and interpretation limits.
7. Existing evidence and category contrasts.

The worked examples use realistic fictional situations without brands. Historical cases remain the evidence-bearing layer and clearly distinguish documented facts from retrospective classification.

## Alternatives considered

### Inline worked example — selected

Shows the entire reasoning chain in the normal reading flow. It makes the technique observable, supports comparison between steps, and does not require the learner to discover or operate another control.

### Collapsible worked example

Keeps chapters shorter but recreates the current problem: the most educational content is easy to miss. It is retained only for the longer historical cases.

### Step-by-step wizard

Creates a guided experience but hides the overall causal structure and introduces navigation state that is not necessary for theory reading. It would fit a later interactive exercise better than this chapter.

## Curriculum model

Each category gains four structured fields in addition to the current summary, formula, evidence sections, and contrasts:

```json
{
  "workedExample": {
    "title": "",
    "situation": "",
    "ordinaryQuestion": "",
    "hackerQuestion": "",
    "reasoningSteps": [
      { "label": "", "text": "" }
    ],
    "solution": "",
    "whyItFits": "",
    "confusion": {
      "otherCategory": "",
      "explanation": ""
    }
  },
  "questionTemplates": [
    { "domain": "", "question": "" }
  ],
  "quickExercise": "",
  "experiment": "",
  "cases": [
    {
      "slug": "",
      "title": "",
      "actor": "",
      "period": "",
      "originalFrame": "",
      "frameShift": "",
      "action": "",
      "outcome": "",
      "whyItFits": "",
      "limitations": "",
      "classification": "explicit|research-interpretation",
      "sourceIds": [""]
    }
  ]
}
```

`reasoningSteps` is intentionally generic. Its labels make the method-specific structure explicit without creating seven incompatible API types.

All five researched `questionTemplates` are preserved by the server. The theory UI displays the first two to keep the chapter focused. All three historical cases are returned and rendered as closed disclosures.

## Worked example content

### Inversion

- Situation: a product launch plan assumes that onboarding will work, although early users already abandon it.
- Ordinary question: how can onboarding conversion be improved?
- Hacker question: what would guarantee that forty percent of users abandon onboarding in the first ten minutes?
- Reasoning labels: unwanted outcome, plausible causes, existing signal, reversal into protection.
- Ending: one cause becomes an owner-assigned preventive experiment and an early warning metric.
- Confusion: Backcasting moves backwards from desired success; this example reconstructs failure and reverses its causes.

### Hyperbole / 10×

- Situation: one expert manually triages every complex support request.
- Ordinary question: how can triage be made ten percent faster?
- Hacker question: what mechanism is needed if requests grow twentyfold while the expert remains alone?
- Reasoning labels: changed parameter, broken architecture, new mechanism, real constraints.
- Ending: a bounded routing prototype tests the new mechanism without treating 20× as a forecast.
- Confusion: Simplification begins by testing necessity; this example begins by radically changing a measurable parameter.

### Cross-discipline

- Situation: a clinic loses context when responsibility moves between specialists.
- Ordinary question: how can the handoff form be improved?
- Hacker question: what can be transferred from a relay handoff while preserving the differences of clinical work?
- Reasoning labels: source element, target equivalent, significant difference, testable prediction.
- Ending: a limited handoff protocol tests explicit acceptance and rejects the analogy if context loss does not fall.
- Confusion: Reframing changes the definition of the problem; this example preserves the problem and imports a causal mechanism.

### Backcasting

- Situation: support responds in three days and repeatedly adds operators without changing the process.
- Ordinary question: what support automation should be built this year?
- Hacker question: in June 2030, ninety percent of common problems are resolved in five minutes without contacting support; what must be true immediately before that?
- Reasoning labels: 2030 outcome, 2028 capability, 2027 foundation, action today.
- Ending: test self-diagnosis for one request type this week.
- Confusion: Hyperbole changes one parameter to break the current solution; Backcasting defines a complete desired state and reconstructs prerequisites backwards.

### Provocation

- Situation: every small refund requires manager approval and creates a queue.
- Ordinary question: how can approvals be made faster?
- Hacker question: what becomes possible if prior approval is temporarily removed for low-risk refunds?
- Reasoning labels: cancelled rule, protected function, safety invariants, alternative mechanism, bounded pilot and kill switch.
- Ending: a reversible pilot replaces prior permission with thresholds, transparency, and post-review.
- Confusion: Simplification may remove a step because it is unnecessary; Provocation temporarily cancels a rule to reveal and replace its function.

### Reframing

- Situation: new customers abandon a long setup wizard before receiving value.
- Ordinary question: how can more people finish the wizard?
- Hacker question: how can a customer receive the first verified useful outcome within ten minutes?
- Reasoning labels: original symptom, new outcome, changed evidence, newly visible solutions.
- Ending: a concierge prototype tests time-to-first-outcome rather than form completion.
- Confusion: Cross-discipline imports a mechanism from elsewhere; Reframing changes the unit of success and therefore the solution space.

### Simplification / first principles

- Situation: an internal purchase request contains eleven fields and three approvals inherited from earlier policy.
- Ordinary question: which fields can be removed?
- Hacker question: what information and controls are strictly necessary to make a safe, auditable purchase decision?
- Reasoning labels: verified fact, physical or legal constraint, protective mechanism, historical habit, minimal reconstruction and exception check.
- Ending: one low-risk request type uses a minimal form with rollback and harm signals.
- Confusion: Hyperbole imposes an extreme quantity; Simplification derives a minimal solution from required outcomes and invariants.

## Persistence and API

The canonical curriculum JSON remains the authoring artifact. Curriculum generation copies `questionTemplates`, `quickExercise`, `experiment`, and `cases` from `docs/research/theory-expansion.json` instead of discarding them, and adds the seven editorially reviewed `workedExample` objects.

An additive database migration adds JSON/text columns to `category` for the worked example, question templates, quick exercise, field experiment, and historical cases. JSON is appropriate here because these values are immutable server-owned curriculum content, imported atomically with a category, and never queried independently.

`CurriculumImporter` validates every category before writing it:

- exactly one non-empty worked example;
- three to five non-empty reasoning steps;
- at least two question templates;
- a non-empty exercise and experiment;
- exactly three historical cases;
- every case has at least one known source ID;
- confusion points to another canonical category.

`CurriculumService.CategoryDetail` exposes typed records for worked examples, templates, and cases. Case source IDs are resolved against `evidence_source` and returned as full source objects so the browser never has to join curriculum data itself.

## Interface

The new inline block follows the current paper, graphite, acid, and violet visual language. It uses quiet surface changes and existing border depth rather than adding another decorative card system.

The block contains:

- a neutral situation surface;
- a two-column ordinary-question / hacker-question comparison, stacking on narrow screens;
- a method-specific reasoning chain of three to five labeled steps;
- a prominent solution or experiment surface;
- a smaller explanation surface containing `whyItFits` and the likely confusion;
- two short templates below the main example.

Historical cases use native `<details>` elements. Their summaries show the case title, actor, and period. Expanded content shows the original frame, frame shift, action, result, why it fits, limitations, classification status, and linked sources.

The exercise and field experiment remain visible rather than collapsible. Evidence sections and existing contrasts retain their current presentation.

## Error handling and compatibility

The migration is additive. Existing category fields and endpoints remain available. The frontend treats the new fields defensively so a temporarily incomplete response does not prevent the rest of a theory chapter from rendering.

Missing source IDs are rejected during curriculum import rather than silently producing broken historical citations. Unknown confusion category codes are rejected by the same validation pass.

No new route or client-side state is introduced. Category loading, errors, and authentication continue to use the current curriculum endpoint behavior.

## Verification and acceptance criteria

- The generated curriculum contains one complete worked example, five researched templates, one exercise, one experiment, and three historical cases for each of seven categories.
- The importer persists those fields and remains idempotent.
- The category API returns typed rich theory data and resolved sources.
- The Backcasting example uses a desired, observable future and an explicit reverse timeline ending in an action today.
- Cross-discipline reasoning contains source element, target equivalent, significant difference, and testable prediction.
- Provocation contains rule function, safety invariants, alternative mechanism, bounded pilot, and kill switch.
- Simplification distinguishes facts, constraints, protective mechanisms, and historical habits before reconstruction.
- Inversion ends by reversing a failure cause into a protective action.
- Hyperbole names the broken architecture, proposes a different mechanism, and returns to real constraints.
- The frontend displays the worked example inline, only two short templates, visible exercises, and three closed historical cases.
- Layout tests cover desktop structure, narrow-screen stacking, semantic disclosures, source links, and the absence of raw evidence-grade enum labels in the new content.
- Existing curriculum, frontend, and backend tests continue to pass.

## Out of scope

- Turning the worked example into an interactive exercise with stored progress.
- Editing curriculum content through an admin interface.
- Rewriting the existing evidence research or adding new web sources.
- Replacing the trainer or practice workflows.
