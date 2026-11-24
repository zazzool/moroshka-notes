# Покрытие утверждённого планшета

Референс: [исходный планшет](mockups/04-asset-board.png), [версия пользователя](mockups/05-approved-reference.png). Оригиналы сохранены целиком. Самостоятельные изображения воссозданы по ним и не выдаются за извлечённые слои.

## Изображения и бренд

| Элемент на планшете | Файл для приложения | Исходник / вариант | Происхождение |
| --- | --- | --- | --- |
| Объёмный знак морошки | `assets/raster/brand/cloudberry-3d.png` | `assets/masters/brand/cloudberry-3d.png` | Новая генерация по референсу, настоящий alpha |
| Иконка приложения | `assets/raster/brand/app-icon-3d.png` | Размеры 48, 72, 96, 144, 192, 512; шаблоны в `assets/platform/android/` | Новая генерация по референсу |
| Декоративная веточка | `assets/raster/brand/cloudberry-sprig.png` | `assets/masters/brand/cloudberry-sprig.png` | Новая генерация по референсу, настоящий alpha |
| Монохромные знаки и логотипы | `assets/brand/` | Редактируемые SVG | Кодовая векторная версия |
| Морошка на фоне гор и озера | `assets/raster/backgrounds/cloudberry-landscape-landscape.webp` | `cloudberry-landscape-portrait.webp`, JPEG и PNG-мастера | Воссозданная сцена |
| Горный рассвет | `assets/raster/backgrounds/mountain-dawn-landscape.webp` | `mountain-dawn-portrait.webp`, JPEG и PNG-мастера | Воссозданная сцена |
| Озеро на закате | `assets/raster/backgrounds/lake-sunset-landscape.webp` | `lake-sunset-portrait.webp`, JPEG и PNG-мастера | Воссозданная сцена |
| Звёздное небо | `assets/raster/backgrounds/starry-night-landscape.webp` | `starry-night-portrait.webp`, JPEG и PNG-мастера | Воссозданная сцена |
| Крупный план ягоды | `assets/raster/backgrounds/cloudberry-macro-landscape.webp` | `cloudberry-macro-portrait.webp`, JPEG и PNG-мастера | Воссозданная сцена |
| Светящийся мозг | `assets/raster/illustrations/brain-glow.webp` | `assets/masters/illustrations/brain-glow.png` | Художественная иллюстрация, не научная диаграмма |
| Раскрытая светящаяся книга | `assets/raster/illustrations/knowledge-book.webp` | `assets/masters/illustrations/knowledge-book.png` | Новая генерация по референсу |
| Нейронная сеть | `assets/raster/illustrations/neural-network.webp` | `assets/masters/illustrations/neural-network.png` | Новая генерация по референсу |
| Композиция из камней | `assets/raster/illustrations/balance-stones.webp` | `assets/masters/illustrations/balance-stones.png` | Новая генерация по референсу |
| Дерево на закате | `assets/raster/illustrations/idea-tree.webp` | `assets/masters/illustrations/idea-tree.png` | Новая генерация по референсу |

У всех пяти фонов есть отдельные широкие и портретные композиции. Экспорт: 1600×900 и 900×1600. Исходные генерации сохранены; когда их пропорции отличаются, экспорт использует cover-кадрирование, а не растяжение. Прямоугольники записаны в `assets/raster/export-map.json`.

## Все 27 позиций системы иконок

Каждая ячейка соответствует позиции на планшете слева направо. Путь — `assets/icons/<имя>.svg`.

| Ряд | Позиции |
| --- | --- |
| 1 | `home`, `search`, `plus`, `library`, `graph`, `layers`, `save` |
| 2 | `checkbox`, `calendar`, `folder`, `tag`, `link`, `mic`, `waveform` |
| 3 | `image`, `play`, `paperclip`, `settings`, `bell`, `user`, `attachment` |
| 4 | `lightning`, `brain`, `heart`, `sun`, `moon`, `stopwatch` |

Две скрепки переданы как `paperclip` и `attachment`. Это варианты знака; их наличие не означает двух новых функций. Дополнительно сохранены `note`, `sparkles`, `check`, `close`, `edit`, `send`, `pause`, `stop`, `back`, `more`.

Машиночитаемая карта: `assets/icons-coverage.json`. PNG-экспорты: `assets/icons-png/`.

## Компоненты и оформление

| Элемент | Где искать |
| --- | --- |
| Основная/вторичная кнопка, круглые действия | `assets/components/` |
| Теги, активный тег, удаление | `assets/components/` |
| Переключатели, шкалы, прогресс | `assets/components/` |
| Карточки заметки, предложений ИИ и связи | `assets/components/` |
| Стеклянные поверхности, градиенты, свечение | `assets/effects/` |
| Узлы и рёбра графа | Примеры в `assets/components/`; данные предоставляет приложение |
| Голосовая волна и состояние записи | `assets/components/`, `assets/icons/waveform.svg` |
| Нижняя навигация | `assets/components/`, четыре согласованных раздела |
| Типографика | `assets/fonts/Manrope-Variable.ttf`, `OFL.txt`, `tokens.json` |
| Светлая/тёмная тема и состояния | HTML/CSS-примеры |
| Редактируемая библиотека | `assets/components/ui-kit.svg`, HTML/CSS; без заявления о нативном `.fig` |
| Три ранних макета | `mockups/01.png`, `02.png`, `03.png` — оригиналы |
| Полный планшет | `mockups/04-asset-board.png` — оригинал |

Для внедрения приоритет у согласованных пользовательских сценариев в `SPEC.md` и `HANDOFF.md`. Вымышленные счётчики и примеры медицинских заметок не переносить в пользовательскую базу.
