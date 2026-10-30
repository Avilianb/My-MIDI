/* Exercise the production bridge with a deterministic USB FIFO / GAP model. */
#include <assert.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "../main/midi_bridge.c"
struct TestQueue { unsigned size, count, depth; unsigned char *data; };
QueueHandle_t xQueueCreate(unsigned n,unsigned size) {
    QueueHandle_t q=calloc(1,sizeof(*q)); q->depth=n;q->size=size;q->data=calloc(n,size);return q;
}
int xQueueSend(QueueHandle_t q,const void *item,unsigned wait) {
    assert(wait==0);if(q->count==q->depth)return 0;
    memcpy(q->data+q->size*q->count++,item,q->size);return 1;
}
int xQueueReceive(QueueHandle_t q,void *item,unsigned wait) {
    assert(wait==0);if(!q->count)return 0;
    memcpy(item,q->data,q->size);q->count--;memmove(q->data,q->data+q->size,q->count*q->size);return 1;
}
void xQueueReset(QueueHandle_t q){q->count=0;}
void vQueueDelete(QueueHandle_t q){free(q->data);free(q);}
static bool mounted=true, ready[2]={true,true}, blocked;
static uint32_t generations[2]={1,1};
static unsigned sent[2], off[2], cc[2], ble_sent, ble_max;
static int ble_result=ESP_OK;
bool usb_midi_device_mounted(void){return mounted;}
uint32_t usb_midi_write(uint8_t c,const uint8_t*d,uint32_t n){
    if(blocked)return 0;
    sent[c]++;if((d[0]&0xf0)==0x80)off[c]++;if((d[0]&0xf0)==0xb0)cc[c]++;return n;
}
uint32_t usb_midi_read_packets(uint8_t*p,uint32_t n){(void)p;(void)n;return 0;}
bool ble_midi_link_port_ready(uint8_t c){return ready[c];}
uint32_t ble_midi_link_cable_generation(uint8_t c){return generations[c];}
esp_err_t ble_midi_link_send(uint8_t c,const uint8_t *const m[],const uint8_t l[],uint16_t n){
    (void)c;(void)m;(void)l;ble_sent+=n;if(n>ble_max)ble_max=n;return ble_result;
}
void ble_midi_link_get_stats(ble_midi_link_stats_t*s){memset(s,0,sizeof(*s));}
static void reset(void){
    clear_queues();memset(s_recovery_requested,0,sizeof(s_recovery_requested));
    memset(s_recovery_epoch,0,sizeof(s_recovery_epoch));
    memset(sent,0,sizeof(sent));memset(off,0,sizeof(off));memset(cc,0,sizeof(cc));
    for(unsigned c=0;c<2;c++){generations[c]=1;s_cable_generation_seen[c]=1;ready[c]=true;}
    blocked=false;mounted=true;
}
static midi_msg_t note(bool on){
    midi_msg_t m={.cable_num=0,.len=3,.data={on?0x90:0x80,60,on?127:0},.generation=1};return m;
}
int main(void){
    assert(midi_bridge_init()==ESP_OK);
    reset();
    midi_msg_t on=note(true), release=note(false);
    process_ble_message(&on);assert(sent[0]==1);
    blocked=true;usb_send_panic_cable(0);flush_pending_note_offs(0);
    assert(s_pending_note_off_any[0]);assert(s_pending_note_off[0][0][0]&(1ULL<<60));
    blocked=false;
    for(int i=0;i<300 && s_pending_note_off_any[0];i++){
        unsigned before=sent[0];flush_pending_note_offs(0);assert(sent[0]-before<=PANIC_BATCH);
    }
    assert(!s_pending_note_off_any[0]);assert(cc[0]==48);assert(off[0]==1);assert(sent[1]==0);
    puts("PASS panic retains FIFO failures, releases exact notes, bounded work and cable isolation");
    reset();generations[0]++;process_ble_message(&on);assert(sent[0]==0);
    puts("PASS queued old-owner Note On cannot reach new owner");
    reset();process_ble_message(&on);blocked=true;process_ble_message(&release);
    assert(s_recovery_requested[0]);assert(s_recovery_epoch[0]==1);
    blocked=false;process_ble_message(&on);assert(sent[0]==1);
    puts("PASS failed Note Off invalidates backlog instead of later replaying Note On");
    reset();
    for(int i=0;i<BLE_TO_USB_DEPTH;i++)midi_bridge_on_ble_midi(0,on.data,on.len,0);
    midi_bridge_on_ble_midi(0,release.data,release.len,0);
    assert(s_recovery_requested[0]);assert(s_recovery_epoch[0]==1);
    assert(s_recovery_epoch[1]==0);
    puts("PASS full callback queue never blocks and requests per-cable recovery");
    reset();shadow_clear();
    for(int i=0;i<128;i++) {midi_msg_t m={.len=3,.data={0xB0,i,i}};shadow_record(&m);}
    ble_result=ESP_FAIL;ble_sent=ble_max=0;s_shadow_replay_request[0]++;
    shadow_replay(0);assert(s_replay_cursor[0]==0);assert(ble_max<=8);
    ble_result=ESP_OK;shadow_replay(0);assert(s_replay_cursor[0]==8);
    assert(s_shadow_replay_request[0]!=s_shadow_replay_completed[0]);assert(ble_max<=8);
    puts("PASS replay retries failed batch and bounds each turn to eight messages");
    puts("5 bridge regression scenarios passed");return 0;
}
