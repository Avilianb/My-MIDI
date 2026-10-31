#pragma once
#include "FreeRTOS.h"
typedef void *TaskHandle_t;
static inline void vTaskDelay(unsigned ticks) {(void)ticks;}
static inline void xTaskNotifyGive(TaskHandle_t h) {(void)h;}
static inline unsigned ulTaskNotifyTake(int clear, unsigned ticks) {(void)clear;(void)ticks;return 0;}
static inline int xTaskCreate(void(*f)(void*),const char*n,unsigned s,void*a,unsigned p,TaskHandle_t*h) {(void)f;(void)n;(void)s;(void)a;(void)p;*h=(void*)1;return 1;}
static inline void vTaskDelete(TaskHandle_t h) {(void)h;}
