<template>
    <div class="_fd-event">
        <el-badge :value="eventNum" type="warning" :hidden="eventNum < 1">
            <el-button size="small" @click="visible=true">{{ t('event.title') }}</el-button>
        </el-badge>
        <el-dialog class="_fd-event-dialog" v-model="visible" destroy-on-close
                   :close-on-click-modal="false"
                   append-to-body
                   width="1080px">
            <template #header>
                <div class="_fd-event-dialog-head">
                    <span class="el-dialog__title">{{ t('event.title') }}</span>
                    <DesignerHelpLink
                        path="/form-events#when"
                        :aria-label="eventGuideAria"
                        test-id="form-events-guide-link"
                    />
                </div>
            </template>
            <el-container class="_fd-event-con" style="height: 600px">
                <el-aside style="width:300px;">
                    <el-container class="_fd-event-l">
                        <el-header class="_fd-event-head" height="40px">
                            <el-dropdown popper-class="_fd-event-dropdown" trigger="click" size="default"
                                         :placement="'bottom-start'">
                              <span class="el-dropdown-link">
                                <el-button link type="primary" size="default">
                                    {{ t('event.create') }}<i class="el-icon-arrow-down el-icon--right"></i>
                                </el-button>
                              </span>
                                <template #dropdown>
                                    <el-dropdown-menu>
                                        <el-dropdown-item v-for="name in eventList" :key="name" @click="add(name)"
                                                          :disabled="Object.keys(event).indexOf(name) > -1">
                                            <div class="_fd-event-item">
                                                <span>{{ name }}</span>
                                                <span class="_fd-label" v-if="eventInfo[name]">
                                                    {{ eventInfo[name] }}
                                                </span>
                                            </div>
                                        </el-dropdown-item>
                                        <template v-for="(hook, idx) in hookList">
                                            <el-dropdown-item :divided="eventList.length > 0 && !idx"
                                                              @click="add(hook)"
                                                              :disabled="Object.keys(event).indexOf(hook) > -1">
                                                <div class="_fd-event-item">
                                                    <div> {{ hook }}</div>
                                                    <span class="_fd-label">
                                                    {{ eventInfo[hook] }}
                                                </span>
                                                </div>
                                            </el-dropdown-item>
                                        </template>
                                        <el-dropdown-item :divided="eventList.length > 0" @click="cusEvent">
                                            <div>{{ t('props.custom') }}</div>
                                        </el-dropdown-item>
                                    </el-dropdown-menu>
                                </template>
                            </el-dropdown>
                        </el-header>
                        <el-main>
                            <el-menu
                                :default-active="defActive"
                                v-model="activeData">
                                <template v-for="(item, name) in event">
                                    <template v-if="Array.isArray(item)">
                                        <template v-for="(event, index) in item" :key="name + index">
                                            <el-menu-item :index="name + index">
                                                <div class="_fd-event-title"
                                                     @click.stop="edit({name, item, index})">
                                                    <div class="_fd-event-method">
                                                        <span>function<span>{{
                                                                name
                                                            }}</span></span>
                                                        <span class="_fd-label"
                                                              v-if="eventInfo[name]">{{ eventInfo[name] }}</span>
                                                    </div>
                                                    <i class="fc-icon icon-delete"
                                                       @click.stop="rm({name, item, index})"></i>
                                                </div>
                                            </el-menu-item>
                                        </template>
                                    </template>
                                    <el-menu-item v-else :index="name + 0">
                                        <div class="_fd-event-title" @click.stop="edit({name})">
                                            <div class="_fd-event-method">
                                                <span>function<span>{{
                                                        name
                                                    }}</span></span>
                                                <span class="_fd-label"
                                                      v-if="eventInfo[name]">{{ eventInfo[name] }}</span>
                                            </div>
                                            <i class="fc-icon icon-delete" @click.stop="rm({name})"></i>
                                        </div>
                                    </el-menu-item>
                                </template>
                                <el-menu-item v-if="cus" style="padding-left: 10px;" index="custom">
                                    <div class="_fd-event-title" @click.stop>
                                        <el-input type="text" v-model="cusValue" size="default"
                                                  @keydown.enter="addCus"
                                                  :placeholder="t('event.placeholder')">
                                        </el-input>
                                        <div>
                                            <i class="fc-icon icon-add" @click.stop="addCus"></i>
                                            <i class="fc-icon icon-delete" @click.stop="closeCus"></i>
                                        </div>
                                    </div>
                                </el-menu-item>
                            </el-menu>
                        </el-main>
                    </el-container>
                </el-aside>
                <el-main>
                    <el-container class="_fd-event-r">
                        <el-header class="_fd-event-head" height="40px" v-if="activeData">
                            <div><a target="_blank" href="https://form-create.com/v3/instance/">{{t('form.document')}}</a></div>
                            <div>
                                <el-button size="small" @click="close">{{ t('props.cancel') }}</el-button>
                                <el-button size="small" type="primary" @click="save" color="#2f73ff">{{
                                        t('props.save')
                                    }}
                                </el-button>
                            </div>
                        </el-header>
                        <el-main v-if="activeData">
                            <FnEditor ref="fn" v-model="eventStr" body fnx :name="activeData.name"
                                      :args="fnArgs"
                                      style="height: 558px;"/>
                        </el-main>
                    </el-container>
                </el-main>
            </el-container>
            <template #footer>
                <div>
                    <el-button size="default" @click="cancelDialog">{{ t('props.cancel') }}</el-button>
                    <el-button type="primary" size="default" @click="submit" color="#2f73ff">{{
                            t('props.ok')
                        }}
                    </el-button>
                </div>
            </template>
        </el-dialog>
    </div>
</template>

<script>
import unique from '@form-create/utils/lib/unique';
import {defineComponent} from 'vue';
import FnEditor from '@form-create/designer/src/components/FnEditor.vue';
import {getInjectArg} from '@form-create/designer/src/utils';
import {normalizeEventEditorBody} from '@/utils/formCreateDefaultEvents';
import {loadEventData, parseEventData} from '@/composables/hermesEventConfig/eventSerialization';
import DesignerHelpLink from '@/components/designer/DesignerHelpLink.vue';
import i18n from '@/i18n';

export default defineComponent({
    name: 'HermesEventConfig',
    emits: ['update:modelValue'],
    props: {
        modelValue: [Object, undefined, null],
        componentName: String,
        eventName: {
            type: Array,
            default: () => []
        }
    },
    inject: ['designer'],
    components: {
        FnEditor,
        DesignerHelpLink,
    },
    data() {
        return {
            visible: false,
            activeData: null,
            val: null,
            defActive: 'no',
            hookList: ['hook_load', 'hook_mounted', 'hook_deleted', 'hook_watch', 'hook_value', 'hook_hidden', 'hook_titleClick'],
            event: {},
            cus: false,
            cusValue: '',
            eventStr: '',
            /** Footer Cancel only — X / OK still persist dialog edits to activeRule._on / _hook. */
            discardOnClose: false,
        };
    },
    computed: {
        t() {
            return this.designer.setupState.t;
        },
        eventGuideAria() {
            return i18n.global.t('form.eventGuideLinkAria');
        },
        activeRule() {
            return this.designer.setupState.activeRule;
        },
        orgEvent() {
            if (!this.eventName || !this.eventName.length) {
                return ['click'];
            }
            return this.eventName;
        },
        eventList() {
            return this.orgEvent.map(v => {
                return typeof v === 'object' ? v.name : v;
            })
        },
        eventInfo() {
            const info = {};
            this.orgEvent.forEach(v => {
                if (typeof v === 'object') {
                    info[v.name] = this.t('com.' + this.componentName + '.event.' + v.name) || v.info || this.t('eventInfo.' + v.name) || '';
                } else {
                    info[v] = this.t('com.' + this.componentName + '.event.' + v) || this.t('eventInfo.' + v) || '';
                }
            })
            this.hookList.forEach(v => {
                info[v] = this.t('eventInfo.' + v) || '';
            })
            return info;
        },
        eventNum() {
            let num = 0;
            Object.keys(this.modelValue || {}).forEach(k => {
                num += Array.isArray(this.modelValue[k]) ? this.modelValue[k].length : 1;
            });
            const hooks = this.activeRule ? {...this.activeRule._hook || {}} : {};
            Object.keys(hooks).forEach(k => {
                num += Array.isArray(hooks[k]) ? hooks[k].length : 1;
            });
            return num;
        },
        fnArgs() {
            return [getInjectArg(this.t)];
        }
    },
    watch: {
        visible(v) {
            if (v) {
                this.discardOnClose = false;
                this.event = this.loadFN();
                this.openFirstEvent();
                return;
            }
            if (!this.discardOnClose) {
                this.applyEventsToRule();
            }
            this.discardOnClose = false;
            this.event = {};
            this.destroy();
            this.closeCus();
        },
    },
    methods: {
        addCus() {
            const val = this.cusValue && this.cusValue.trim();
            if (val) {
                this.closeCus();
                this.add(val);
            }
        },
        closeCus() {
            this.cus = false;
            this.cusValue = '';
        },
        cusEvent() {
            this.cus = true;
        },
        loadFN() {
            return loadEventData(this.modelValue, this.activeRule);
        },
        openFirstEvent() {
            const names = Object.keys(this.event);
            if (!names.length) return;
            const name = names.includes('change') ? 'change' : names[0];
            const item = this.event[name];
            if (Array.isArray(item)) {
                this.edit({ name, item, index: 0 });
                return;
            }
            this.edit({ name });
        },
        parseFN(e) {
            return parseEventData(e);
        },
        add(name) {
            let data = {};
            if (Array.isArray(this.event[name])) {
                this.event[name].push('');
                data = {
                    name,
                    item: this.event[name],
                    index: this.event[name].length - 1,
                };
            } else if (this.event[name]) {
                const arr = [this.event[name], ''];
                this.event[name] = arr;
                data = {
                    name,
                    item: arr,
                    index: 1,
                };
            } else {
                const arr = [''];
                this.event[name] = arr;
                data = {
                    name,
                    item: arr,
                    index: 0,
                };
            }
            if (!this.activeData) {
                this.edit(data);
            }
        },
        edit(data) {
            data.key = unique();
            if (data.item) {
                this.val = data.item[data.index];
            } else {
                this.val = this.event[data.name];
            }
            this.activeData = data;
            this.eventStr = this.val;
            this.defActive = data.name + (data.index || 0);
        },
        commitActiveEditor() {
            if (!this.activeData || !this.$refs.fn) {
                return true;
            }
            if (!this.$refs.fn.save()) {
                const raw =
                    (this.$refs.fn.editor && this.$refs.fn.editor.getValue())
                    || this.eventStr
                    || '';
                const fallback = normalizeEventEditorBody(raw);
                if (!fallback && raw.trim() !== '') {
                    return false;
                }
                const str = fallback;
                if (this.activeData.item) {
                    this.activeData.item[this.activeData.index] = str;
                } else {
                    this.event[this.activeData.name] = str;
                }
                this.destroy();
                return true;
            }
            const str = normalizeEventEditorBody(this.$refs.fn.fn ?? this.eventStr);
            if (this.activeData.item) {
                this.activeData.item[this.activeData.index] = str;
            } else {
                this.event[this.activeData.name] = str;
            }
            this.destroy();
            return true;
        },
        save() {
            this.commitActiveEditor();
        },
        rm(data) {
            if (data.index !== undefined) {
                data.item.splice(data.index, 1);
                if(data.item.length === 0) {
                    delete this.event[data.name];
                }
            } else {
                delete this.event[data.name];
            }
            if (this.defActive === (data.name + (data.index || 0))) {
                this.destroy();
            }
        },
        destroy() {
            this.activeData = null;
            this.val = null;
            this.defActive = 'no';
        },
        close() {
            this.destroy();
        },
        cancelDialog() {
            this.discardOnClose = true;
            this.visible = false;
        },
        applyEventsToRule() {
            if (this.activeData && !this.commitActiveEditor()) {
                return false;
            }
            const {on, hooks} = this.parseFN(this.event);
            const rule = this.activeRule;
            if (!rule) {
                return false;
            }
            rule._on = on;
            rule._hook = hooks;
            this.$emit('update:modelValue', on);
            return true;
        },
        submit() {
            if (!this.applyEventsToRule()) {
                return;
            }
            this.visible = false;
        },
    },
    beforeCreate() {
        window.$inject = {
            $f: {},
            rule: [],
            self: {},
            option: {},
            inject: {},
            args: [],
        };
    }
});
</script>

<style>

._fd-event .el-button {
    font-weight: 400;
    width: 100%;
    border-color: #2E73FF;
    color: #2E73FF;
}

._fd-event .el-badge {
    width: 100%;
}

._fd-event-dialog .el-dialog__body {
    padding: 10px 20px;
}

._fd-event-con .el-main {
    padding: 0;
}

._fd-event-l, ._fd-event-r {
    display: flex;
    flex-direction: column;
    flex: 1;
    height: 100%;
    border: 1px solid #ececec;
}

._fd-event-dropdown .el-dropdown-menu {
    max-height: 500px;
    overflow: auto;
}

._fd-event-head {
    display: flex;
    padding: 5px 15px;
    border-bottom: 1px solid #eee;
    background: #f8f9ff;
    align-items: center;
}

._fd-event-head .el-button.is-link {
    color: #2f73ff;
}

._fd-event-r {
    border-left: 0 none;
}

._fd-event-r ._fd-event-head {
    justify-content: space-between;
}

._fd-event-l > .el-main, ._fd-event-r > .el-main {
    display: flex;
    flex-direction: row;
    flex: 1;
    flex-basis: auto;
    box-sizing: border-box;
    min-width: 0;
    width: 100%;
}

._fd-event-r > .el-main {
    flex-direction: column;
}

._fd-event-item {
    display: flex;
    flex-direction: column;
    justify-content: center;
    max-width: 250px;
    font-size: 14px;
    overflow: hidden;
    white-space: pre-wrap;
}

._fd-event-item ._fd-label {
    font-size: 12px;
    color: #AAAAAA;
}

._fd-event-l .el-menu {
    padding: 0 10px 5px;
    border-right: 0 none;
    width: 100%;
    border-top: 0 none;
    overflow: auto;
}

._fd-event-l .el-menu-item.is-active {
    background: #e4e7ed;
    color: #303133;
}

._fd-event-dialog-head {
    display: flex;
    align-items: center;
    gap: 8px;
}

._fd-event-l .el-menu-item {
    height: auto;
    line-height: 1em;
    border: 1px solid #ECECEC;
    border-radius: 5px;
    padding: 0;
    margin-top: 5px;
}

._fd-event-method {
    display: flex;
    flex-direction: column;
    justify-content: center;
    width: 225px;
    font-size: 14px;
    font-family: monospace;
    color: #9D238C;
    overflow: hidden;
    white-space: pre-wrap;
}

._fd-event-method ._fd-label {
    margin-top: 4px;
    color: #AAAAAA;
    font-size: 12px;
}

._fd-event-method > span:first-child, ._fd-fn-list-method > span:first-child {
    color: #9D238C;
}

._fd-event-method > span:first-child > span, ._fd-fn-list-method > span:first-child > span {
    color: #000;
    margin-left: 10px;
}

._fd-event-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    padding: 10px 0;
}

._fd-event-title .fc-icon {
    margin-right: 6px;
    font-size: 18px;
    color: #282828;
}

._fd-event-title .el-input {
    width: 200px;
}

._fd-event-title .el-input__wrapper {
    box-shadow: none;
}

._fd-event-title .el-menu-item.is-active i {
    color: #282828;
}

._fd-event-con .CodeMirror {
    height: 100%;
    width: 100%;
}

._fd-event-con .CodeMirror-wrap pre.CodeMirror-line {
    padding-left: 20px;
}
</style>
