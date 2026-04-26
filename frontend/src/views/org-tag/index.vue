<script setup lang="tsx">
import { NButton, NPopconfirm } from 'naive-ui';
import { fetchBulkImportOrgTags } from '@/service/api/auth';
import OrgTagOperateDialog from './modules/org-tag-operate-dialog.vue';

const appStore = useAppStore();

const { columns, columnChecks, data, loading, getData } = useTable({
  apiFn: fetchGetOrgTagList,
  columns: () => [
    {
      key: 'name',
      title: '标签名称',
      width: 300,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'description',
      title: '描述',
      minWidth: 200,
      ellipsis: {
        tooltip: true
      }
    },
    {
      key: 'operate',
      title: '操作',
      width: 240,
      render: row => (
        <div class="flex gap-2">
          <NButton type="success" ghost size="small" onClick={() => addChild(row)}>
            新增下级
          </NButton>
          <NButton type="primary" ghost size="small" onClick={() => edit(row)}>
            编辑
          </NButton>
          <NPopconfirm onPositiveClick={() => handleDelete(row.tagId!)}>
            {{
              default: () => '确认删除当前标签吗？',
              trigger: () => (
                <NButton type="error" ghost size="small">
                  删除
                </NButton>
              )
            }}
          </NPopconfirm>
        </div>
      )
    }
  ]
});

const {
  dialogVisible,
  operateType,
  editingData,
  handleAdd,
  handleAddChild,
  handleEdit,
  onDeleted
  // closeDrawer
} = useTableOperate<Api.OrgTag.Item>(getData);

function addChild(row: Api.OrgTag.Item) {
  handleAddChild(row);
}

/** the editing row data */
function edit(row: Api.OrgTag.Item) {
  handleEdit(row);
}

async function handleDelete(tagId: string) {
  const { error } = await request({ url: `/admin/org-tags/${tagId}`, method: 'DELETE' });
  if (!error) {
    onDeleted();
  }
}

// ====== 批量导入 CSV ======
const importModalVisible = ref(false);
const importCsv = ref('');
const importSubmitting = ref(false);
const importResult = ref<{ inserted: number; updated: number; failed: number } | null>(null);
const importFileInput = ref<HTMLInputElement | null>(null);
const CSV_TEMPLATE = [
  '# type,tagId,name,parentTagId,description',
  'SCHOOL,SCHOOL_THU,清华大学,,',
  'COLLEGE,COLLEGE_THU_CS,计算机科学与技术系,SCHOOL_THU,'
].join('\n');

function openImportModal() {
  importCsv.value = '';
  importResult.value = null;
  importModalVisible.value = true;
}

function pickImportFile() {
  importFileInput.value?.click();
}

async function onImportFilePicked(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  const text = await file.text();
  importCsv.value = text;
}

function fillTemplate() {
  importCsv.value = CSV_TEMPLATE;
}

async function submitImport() {
  const csv = importCsv.value.trim();
  if (!csv) {
    window.$message?.error('请粘贴或上传 CSV 内容');
    return;
  }
  importSubmitting.value = true;
  const { error, data } = await fetchBulkImportOrgTags(csv);
  importSubmitting.value = false;
  if (error) return;
  importResult.value = data || null;
  window.$message?.success(`导入完成：新增 ${data?.inserted ?? 0}，更新 ${data?.updated ?? 0}，失败 ${data?.failed ?? 0}`);
  getData();
}
</script>

<template>
  <div class="flex-col-stretch gap-16px overflow-hidden <sm:overflow-auto">
    <NCard title="组织标签" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <div class="flex items-center gap-2">
          <NButton size="small" type="primary" ghost @click="openImportModal">批量导入 CSV</NButton>
          <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleAdd" @refresh="getData" />
        </div>
      </template>
      <NDataTable
        remote
        :columns="columns"
        :data="data"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="962"
        :loading="loading"
        :pagination="false"
        :row-key="item => item.tagId"
        class="sm:h-full"
      />
      <OrgTagOperateDialog
        v-model:visible="dialogVisible"
        :operate-type="operateType"
        :row-data="editingData!"
        :data="data"
        @submitted="getData"
      />
    </NCard>

    <NModal
      v-model:show="importModalVisible"
      preset="card"
      title="批量导入学校 / 学院"
      style="width: 640px"
      :mask-closable="!importSubmitting"
    >
      <NAlert type="info" :show-icon="true" class="mb-3">
        每行一条记录，列顺序：
        <code>type,tagId,name,parentTagId,description</code>。
        type 为 <code>SCHOOL</code> 或 <code>COLLEGE</code>；COLLEGE 行的 parentTagId 必须指向已存在或本次同文件中的学校。
        以 <code>#</code> 开头或首行为表头的行会被跳过。相同 tagId 会被覆盖更新。
      </NAlert>
      <div class="mb-2 flex items-center gap-2">
        <NButton size="tiny" @click="pickImportFile">选择 CSV 文件</NButton>
        <NButton size="tiny" @click="fillTemplate">填充模板</NButton>
        <input
          ref="importFileInput"
          type="file"
          accept=".csv,text/csv,text/plain"
          class="hidden"
          @change="onImportFilePicked"
        />
      </div>
      <NInput
        v-model:value="importCsv"
        type="textarea"
        :autosize="{ minRows: 10, maxRows: 18 }"
        placeholder="在此粘贴 CSV 内容，或通过上方按钮上传文件"
      />
      <div v-if="importResult" class="mt-3 text-sm">
        导入结果：新增 <b>{{ importResult.inserted }}</b>，更新 <b>{{ importResult.updated }}</b>，失败
        <b>{{ importResult.failed }}</b>。
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <NButton :disabled="importSubmitting" @click="importModalVisible = false">关闭</NButton>
          <NButton type="primary" :loading="importSubmitting" @click="submitImport">开始导入</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>

<style scoped></style>
