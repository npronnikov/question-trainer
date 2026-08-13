Создай ровно {{count}} учебных карточек для распознавания семи техник нестандартного мышления. Верни только один JSON-массив без Markdown.

Категории объектов по порядку: {{categories}}. Не меняй порядок. Для каждого объекта значения category и correctCategory обязаны совпадать с категорией в соответствующей позиции.

Допустимые canonical категории: INVERSION, HYPERBOLE, CROSS_DISCIPLINE, BACKCASTING, PROVOCATION, REFRAMING, SIMPLIFICATION. Сложность: L1, L2 или L3.

Схема каждого объекта:
{"category":"INVERSION","secondaryCategory":null,"difficulty":"L2","domain":"ПРОДУКТ","situation":"80–900 символов","question":"вопрос-взломщик","hint":"направление без правильного ответа","options":["INVERSION","HYPERBOLE","REFRAMING","SIMPLIFICATION"],"correctCategory":"INVERSION","explanation":"основная операция","confusedWith":null,"contrast":null}

Требования: одна основная техника; четыре уникальных варианта с правильным; hint не называет ответ; реалистичная безопасная ситуация без брендов; L3 обязательно содержит confusedWith и конкретный contrast. Не копируй известные, встроенные или ранее сгенерированные учебные примеры.
