# 1.1.6

- GUI mouse actions update Minecraft's internal pointer position as well as dispatching the screen event. Hover tooltips and ability-wheel selection now use the requested GUI coordinates.
- Input remains process-local in isolated sessions. The fix does not move the operating-system pointer, capture native input or change window focus.
