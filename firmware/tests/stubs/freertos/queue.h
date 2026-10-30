#pragma once
#include "FreeRTOS.h"
typedef struct TestQueue *QueueHandle_t;
QueueHandle_t xQueueCreate(unsigned n, unsigned size);
int xQueueSend(QueueHandle_t q, const void *item, unsigned wait);
int xQueueReceive(QueueHandle_t q, void *item, unsigned wait);
void xQueueReset(QueueHandle_t q);
void vQueueDelete(QueueHandle_t q);
