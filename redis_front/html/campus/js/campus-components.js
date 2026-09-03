Vue.component("section-panel", {
  props: {
    title: { type: String, required: true },
    eyebrow: { type: String, default: "" },
    meta: { type: String, default: "" },
    span: { type: String, default: "" }
  },
  template: `
    <section class="panel" :class="span">
      <header class="panel-header">
        <div>
          <p v-if="eyebrow" class="panel-eyebrow">{{ eyebrow }}</p>
          <h2>{{ title }}</h2>
        </div>
        <div class="panel-actions">
          <span v-if="meta" class="panel-meta">{{ meta }}</span>
          <slot name="actions"></slot>
        </div>
      </header>
      <slot></slot>
    </section>
  `
});

Vue.component("status-banner", {
  props: {
    message: { type: String, default: "" },
    type: { type: String, default: "info" },
    busy: { type: Boolean, default: false }
  },
  template: `
    <section class="status-banner" :class="[type, {busy}]" role="status" aria-live="polite">
      <span class="status-dot" aria-hidden="true"></span>
      <span>{{ message }}</span>
    </section>
  `
});

Vue.component("empty-state", {
  props: {
    title: { type: String, required: true },
    description: { type: String, default: "" },
    actionText: { type: String, default: "" }
  },
  template: `
    <div class="empty-state">
      <span class="empty-icon" aria-hidden="true"></span>
      <strong>{{ title }}</strong>
      <p v-if="description">{{ description }}</p>
      <button v-if="actionText" class="button secondary" type="button" @click="$emit('action')">{{ actionText }}</button>
    </div>
  `
});

Vue.component("skeleton-list", {
  props: {
    rows: { type: Number, default: 3 }
  },
  template: `
    <div class="skeleton-list" aria-label="\u52a0\u8f7d\u4e2d">
      <article v-for="row in rows" :key="row" class="skeleton-card">
        <span></span>
        <span></span>
        <span></span>
      </article>
    </div>
  `
});

Vue.component("pager-control", {
  props: {
    page: { type: Object, required: true },
    disabled: { type: Boolean, default: false }
  },
  computed: {
    totalPages() {
      const size = Number(this.page.size || 5);
      const total = Number(this.page.total || 0);
      return Math.max(1, Math.ceil(total / size));
    },
    start() {
      if (!this.page.total) return 0;
      return (Number(this.page.current || 1) - 1) * Number(this.page.size || 5) + 1;
    },
    end() {
      return Math.min(Number(this.page.total || 0), Number(this.page.current || 1) * Number(this.page.size || 5));
    }
  },
  template: `
    <nav class="pager" aria-label="\u5206\u9875">
      <span>{{ start }}-{{ end }} / {{ page.total || 0 }}</span>
      <div class="pager-actions">
        <button class="button secondary" type="button" :disabled="disabled || page.current <= 1" @click="$emit('change', page.current - 1)">\u4e0a\u4e00\u9875</button>
        <span>{{ page.current }} / {{ totalPages }}</span>
        <button class="button secondary" type="button" :disabled="disabled || page.current >= totalPages" @click="$emit('change', page.current + 1)">\u4e0b\u4e00\u9875</button>
      </div>
    </nav>
  `
});

Vue.component("service-point-card", {
  props: {
    point: { type: Object, required: true },
    selected: { type: Boolean, default: false },
    compact: { type: Boolean, default: false },
    reserveText: { type: String, default: "\u9884\u7ea6" },
    showReserve: { type: Boolean, default: true },
    showBoard: { type: Boolean, default: false }
  },
  template: `
    <article
      class="data-card service-card"
      :class="{selected, compact}"
      tabindex="0"
      @click="$emit('select', point)"
      @keyup.enter.self="$emit('select', point)"
      @keyup.space.self.prevent="$emit('select', point)"
    >
      <header class="card-header">
        <div>
          <h3>{{ point.name }}</h3>
          <p>{{ point.area || '-' }} / {{ point.address || '-' }}</p>
        </div>
        <span class="badge success">{{ (point.commentCount || 0) + ' \u6761\u8bc4\u8bba' }}</span>
      </header>
      <dl class="meta-grid">
        <div><dt>\u8425\u4e1a\u65f6\u95f4</dt><dd>{{ point.openHours || '-' }}</dd></div>
        <div><dt>\u8054\u7cfb\u7535\u8bdd</dt><dd>{{ point.phone || '-' }}</dd></div>
      </dl>
      <p class="card-description">{{ point.description || '\u6682\u65e0\u63cf\u8ff0' }}</p>
      <footer class="card-actions">
        <button v-if="showBoard" class="button secondary" type="button" @click.stop="$emit('board', point)">\u7559\u8a00\u677f</button>
        <button v-if="showReserve" class="button" type="button" @click.stop="$emit('reserve', point)">{{ reserveText }}</button>
      </footer>
    </article>
  `
});

Vue.component("slot-card", {
  props: {
    slot: { type: Object, required: true },
    busy: { type: Boolean, default: false },
    formatTime: { type: Function, required: true }
  },
  computed: {
    isFull() {
      return Number(this.slot.availableQuota || 0) <= 0;
    }
  },
  template: `
    <article class="data-card slot-card" :class="{muted: isFull}">
      <header class="card-header">
        <div>
          <h3>{{ slot.title }}</h3>
          <p>{{ formatTime(slot.startTime) }} - {{ formatTime(slot.endTime) }}</p>
        </div>
        <span class="badge warning">{{ slot.availableQuota }}/{{ slot.totalQuota }}</span>
      </header>
      <p class="card-description">{{ slot.description || '\u6682\u65e0\u8bf4\u660e' }}</p>
      <footer class="card-actions">
        <button class="button" type="button" :disabled="busy || isFull" @click="$emit('reserve', slot)">
          {{ busy ? '\u63d0\u4ea4\u4e2d' : '\u7acb\u5373\u9884\u7ea6' }}
        </button>
      </footer>
    </article>
  `
});

Vue.component("ticket-card", {
  props: {
    ticket: { type: Object, required: true },
    statusText: { type: String, required: true },
    admin: { type: Boolean, default: false },
    busyAction: { type: String, default: "" }
  },
  template: `
    <article class="data-card ticket-card" :class="{muted: Number(ticket.adminDeleted || 0) === 1}">
      <header class="card-header">
        <div>
          <h3>{{ ticket.title }}</h3>
          <p v-if="admin">\u7528\u6237 {{ ticket.userId || '-' }} / \u7f51\u70b9 {{ ticket.servicePointId || '-' }}</p>
          <p v-else-if="Number(ticket.adminDeleted || 0) === 1">\u8be5\u5de5\u5355\u5df2\u88ab\u7ba1\u7406\u5458\u5220\u9664</p>
          <p v-else>{{ ticket.content }}</p>
        </div>
        <span class="badge" :class="{'danger-soft': Number(ticket.adminDeleted || 0) === 1}">{{ Number(ticket.adminDeleted || 0) === 1 ? '\u5df2\u5220\u9664' : statusText }}</span>
      </header>
      <p class="card-description" v-if="admin">{{ ticket.content }}</p>
      <p class="card-description danger-text" v-if="Number(ticket.adminDeleted || 0) === 1">\u5220\u9664\u5907\u6ce8\uff1a{{ ticket.deleteRemark || '-' }}</p>
      <dl class="meta-grid">
        <div><dt>\u8054\u7cfb\u65b9\u5f0f</dt><dd>{{ ticket.contactPhone || '-' }}</dd></div>
        <div><dt>\u8be6\u7ec6\u5730\u5740</dt><dd>{{ ticket.detailAddress || '-' }}</dd></div>
        <div><dt>\u9644\u4ef6</dt><dd>{{ ticket.attachmentName || '-' }}</dd></div>
        <div v-if="admin"><dt>\u5b66\u751f\u56de\u590d</dt><dd>{{ Number(ticket.studentReplyRequired || 0) === 1 ? '\u9700\u8981\u56de\u590d' : '\u65e0\u9700\u56de\u590d' }}</dd></div>
      </dl>
      <footer class="card-actions">
        <button v-if="!admin" class="button secondary" type="button" @click="$emit('detail', ticket)">\u8be6\u60c5</button>
        <button v-if="admin" class="button secondary" type="button" @click="$emit('detail', ticket)">\u8be6\u60c5</button>
        <button v-if="ticket.attachmentUrl" class="button secondary" type="button" @click="$emit('download-attachment', ticket)">\u4e0b\u8f7d\u9644\u4ef6</button>
        <button v-if="!admin" class="button danger" type="button" :disabled="busyAction === 'hide'" @click="$emit('hide', ticket)">{{ Number(ticket.adminDeleted || 0) === 1 ? '\u5220\u9664\u8bb0\u5f55' : '\u5220\u9664' }}</button>
        <button v-if="admin && ticket.status === 0" class="button secondary" type="button" :disabled="busyAction === 'accept'" @click="$emit('accept', ticket)">\u53d7\u7406</button>
        <button v-if="admin && (ticket.status === 1 || ticket.status === 2)" class="button secondary" type="button" :disabled="busyAction === 'reply'" @click="$emit('reply', ticket)">\u56de\u590d</button>
        <button v-if="admin && (ticket.status === 0 || ticket.status === 1)" class="button danger" type="button" :disabled="busyAction === 'reject'" @click="$emit('reject', ticket)">\u62d2\u7edd</button>
        <button v-if="admin && ticket.status === 1" class="button" type="button" :disabled="busyAction === 'finish'" @click="$emit('finish', ticket)">\u5b8c\u6210</button>
        <button v-if="admin && ticket.status !== 4 && ticket.status !== 5" class="button danger" type="button" :disabled="busyAction === 'close'" @click="$emit('close', ticket)">\u5173\u95ed</button>
        <button v-if="admin" class="button danger" type="button" :disabled="busyAction === 'delete'" @click="$emit('delete-ticket', ticket)">\u5220\u9664</button>
      </footer>
    </article>
  `
});

Vue.component("ticket-form", {
  props: {
    form: { type: Object, required: true },
    servicePoints: { type: Array, default: () => [] },
    categories: { type: Array, default: () => [] },
    busy: { type: Boolean, default: false },
    uploading: { type: Boolean, default: false },
    errors: { type: Object, default: () => ({}) }
  },
  computed: {
    contentLength() {
      return (this.form.content || "").length;
    }
  },
  template: `
    <form @submit.prevent="$emit('submit')">
      <div class="field-row">
        <div class="field" :class="{'has-error': errors.servicePointId}">
          <label>\u670d\u52a1\u7f51\u70b9 <span>\u5fc5\u586b</span></label>
          <select v-model="form.servicePointId" :aria-invalid="Boolean(errors.servicePointId)" @change="$emit('point-change', form.servicePointId)">
            <option value="">\u8bf7\u9009\u62e9\u5df2\u542f\u7528\u670d\u52a1\u7f51\u70b9</option>
            <option v-for="point in servicePoints" :key="point.id" :value="point.id">{{ point.name }}</option>
          </select>
          <small v-if="errors.servicePointId" class="field-error">{{ errors.servicePointId }}</small>
        </div>
        <div class="field" :class="{'has-error': errors.categoryId}">
          <label>\u670d\u52a1\u5206\u7c7b <span>\u5fc5\u586b</span></label>
          <select v-model="form.categoryId" :aria-invalid="Boolean(errors.categoryId)" @blur="$emit('validate', 'categoryId')">
            <option value="">\u8bf7\u9009\u62e9\u56fa\u5b9a\u5206\u7c7b</option>
            <option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option>
          </select>
          <small v-if="errors.categoryId" class="field-error">{{ errors.categoryId }}</small>
        </div>
      </div>
      <div class="field" :class="{'has-error': errors.title}">
        <label>\u6807\u9898 <span>\u5fc5\u586b</span></label>
        <input v-model.trim="form.title" :aria-invalid="Boolean(errors.title)" @blur="$emit('validate', 'title')">
        <small v-if="errors.title" class="field-error">{{ errors.title }}</small>
      </div>
      <div class="field" :class="{'has-error': errors.content}">
        <label>\u95ee\u9898\u63cf\u8ff0 <span>\u5fc5\u586b</span></label>
        <textarea v-model.trim="form.content" :aria-invalid="Boolean(errors.content)" @blur="$emit('validate', 'content')"></textarea>
        <div class="field-footer">
          <small v-if="errors.content" class="field-error">{{ errors.content }}</small>
          <small class="field-counter">{{ contentLength }}/500</small>
        </div>
      </div>
      <div class="field-row">
        <div class="field" :class="{'has-error': errors.contactPhone}">
          <label>\u8054\u7cfb\u65b9\u5f0f <span>\u5fc5\u586b</span></label>
          <input v-model.trim="form.contactPhone" :aria-invalid="Boolean(errors.contactPhone)" @blur="$emit('validate', 'contactPhone')">
          <small v-if="errors.contactPhone" class="field-error">{{ errors.contactPhone }}</small>
        </div>
        <div class="field" :class="{'has-error': errors.detailAddress}">
          <label>\u8be6\u7ec6\u5730\u5740 <span>\u5fc5\u586b</span></label>
          <input v-model.trim="form.detailAddress" :aria-invalid="Boolean(errors.detailAddress)" @blur="$emit('validate', 'detailAddress')">
          <small v-if="errors.detailAddress" class="field-error">{{ errors.detailAddress }}</small>
        </div>
      </div>
      <div class="field" :class="{'has-error': errors.attachment}">
        <label>\u9644\u4ef6</label>
        <div class="file-control">
          <input type="file" accept=".jpg,.jpeg,.png,.gif,.webp,.bmp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.zip,.rar,.7z" :disabled="uploading || busy" @change="$emit('file-change', $event)">
          <button v-if="form.attachmentName" class="button secondary" type="button" :disabled="uploading || busy" @click="$emit('remove-attachment')">\u79fb\u9664</button>
        </div>
        <small v-if="form.attachmentName" class="field-help">{{ uploading ? '\u4e0a\u4f20\u4e2d...' : form.attachmentName }}</small>
        <small v-if="errors.attachment" class="field-error">{{ errors.attachment }}</small>
      </div>
      <button class="button full-width" type="submit" :disabled="busy">{{ busy ? '\u63d0\u4ea4\u4e2d' : '\u63d0\u4ea4\u5de5\u5355' }}</button>
    </form>
  `
});

Vue.component("orders-table", {
  props: {
    orders: { type: Array, required: true },
    formatTime: { type: Function, required: true },
    statusText: { type: Function, default: null },
    busyAction: { type: String, default: "" },
    selectedOrderId: { type: [Number, String], default: "" }
  },
  methods: {
    canCancel(order) {
      return Number(order.status) === 1;
    },
    isInProgress(order) {
      if (!order || Number(order.status) !== 1 || !order.startTime || !order.endTime) {
        return false;
      }
      const now = Date.now();
      const start = Date.parse(order.startTime);
      const end = Date.parse(order.endTime);
      return !Number.isNaN(start) && !Number.isNaN(end) && start <= now && now < end;
    },
    statusBadgeClass(order) {
      const status = Number(order.status);
      if (status === 1) return "success";
      if (status === 2 || status === 4 || status === 5) return "danger-soft";
      if (status === 3) return "green";
      return "";
    },
    timeRange(order) {
      const start = this.formatTime(order.startTime);
      const end = this.formatTime(order.endTime);
      if (start === "-" && end === "-") {
        return order.slotId || "-";
      }
      return start + " - " + end;
    },
    orderStatusText(order) {
      if (this.statusText) {
        return this.statusText(order);
      }
      return order.statusText || order.status || "-";
    }
  },
  template: `
    <div>
      <empty-state v-if="orders.length === 0" title="\u6682\u65e0\u9884\u7ea6\u8bb0\u5f55" description="\u9884\u7ea6\u6210\u529f\u540e\u4f1a\u663e\u793a\u5728\u6b64\u5904\u3002"></empty-state>
      <div v-else class="table-wrap">
        <table class="appointment-table">
          <thead><tr><th>\u7f16\u53f7</th><th>\u9884\u7ea6\u65f6\u6bb5</th><th>\u670d\u52a1\u70b9</th><th>\u72b6\u6001</th><th>\u5907\u6ce8</th><th>\u521b\u5efa\u65f6\u95f4</th><th>\u64cd\u4f5c</th></tr></thead>
          <tbody>
            <tr v-for="order in orders" :key="order.id" :class="{selected: String(selectedOrderId) === String(order.id)}" :data-order-id="order.id">
              <td>{{ order.id }}</td>
              <td>
                <strong>{{ order.slotTitle || order.slotId }}</strong>
                <small>{{ timeRange(order) }}</small>
              </td>
              <td>
                <strong>{{ order.servicePointName || order.servicePointId || '-' }}</strong>
                <small>{{ order.servicePointAddress || '-' }}</small>
              </td>
              <td><span class="badge" :class="statusBadgeClass(order)">{{ orderStatusText(order) }}</span></td>
              <td>{{ order.remark || '-' }}</td>
              <td>{{ formatTime(order.createTime) }}</td>
              <td>
                <button class="button danger" type="button" v-if="canCancel(order)" :disabled="busyAction === 'cancel:' + order.id" @click="$emit('cancel', order)">
                  {{ busyAction === 'cancel:' + order.id ? '\u53d6\u6d88\u4e2d' : '\u53d6\u6d88' }}
                </button>
                <button class="button danger" type="button" :title="isInProgress(order) ? '\u9884\u7ea6\u8fdb\u884c\u4e2d\uff0c\u4e0d\u80fd\u5220\u9664' : ''" :disabled="busyAction === 'delete:' + order.id || isInProgress(order)" @click="$emit('delete-order', order)">
                  {{ busyAction === 'delete:' + order.id ? '\u5220\u9664\u4e2d' : '\u5220\u9664' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
});

Vue.component("admin-appointment-table", {
  props: {
    orders: { type: Array, required: true },
    formatTime: { type: Function, required: true },
    statusText: { type: Function, required: true },
    busyPrefix: { type: String, default: "appointmentOrder" },
    selectedOrderId: { type: [Number, String], default: "" }
  },
  methods: {
    statusBadgeClass(order) {
      const status = Number(order.status);
      if (status === 1) return "success";
      if (status === 2 || status === 4 || status === 5) return "danger-soft";
      if (status === 3) return "green";
      return "";
    },
    busyKey(order, action) {
      return this.busyPrefix + ":" + order.id + ":" + action;
    },
    isInProgress(order) {
      if (!order || Number(order.status) !== 1 || !order.startTime || !order.endTime) {
        return false;
      }
      const now = Date.now();
      const start = Date.parse(order.startTime);
      const end = Date.parse(order.endTime);
      return !Number.isNaN(start) && !Number.isNaN(end) && start <= now && now < end;
    }
  },
  template: `
    <div>
      <empty-state v-if="orders.length === 0" title="\u6682\u65e0\u9884\u7ea6\u8ba2\u5355" description="\u5f53\u524d\u6743\u9650\u4e0b\u6ca1\u6709\u5f85\u5904\u7406\u6570\u636e\u3002"></empty-state>
      <div v-else class="table-wrap">
        <table class="appointment-table">
          <thead><tr><th>\u9884\u7ea6</th><th>\u670d\u52a1\u70b9</th><th>\u7528\u6237</th><th>\u72b6\u6001</th><th>\u5904\u7406\u5907\u6ce8</th><th>\u5185\u90e8\u5907\u6ce8</th><th>\u64cd\u4f5c</th></tr></thead>
          <tbody>
            <tr v-for="order in orders" :key="order.id" :class="{selected: String(selectedOrderId) === String(order.id)}" :data-order-id="order.id">
              <td>
                <strong>{{ order.slotTitle || order.slotId }}</strong>
                <small>{{ formatTime(order.startTime) }} - {{ formatTime(order.endTime) }}</small>
              </td>
              <td>
                <strong>{{ order.servicePointName || order.servicePointId || '-' }}</strong>
                <small>{{ order.servicePointAddress || '-' }}</small>
              </td>
              <td>{{ order.userId }}</td>
              <td><span class="badge" :class="statusBadgeClass(order)">{{ statusText(order) }}</span></td>
              <td>{{ order.remark || '-' }}</td>
              <td>{{ order.internalRemark || '-' }}</td>
              <td>
                <button class="button secondary" type="button" v-if="Number(order.status) === 1" :disabled="$root.isSubmitting(busyKey(order, 'finish'))" @click="$emit('finish', order)">\u5b8c\u6210</button>
                <button class="button danger" type="button" v-if="Number(order.status) === 1" :disabled="$root.isSubmitting(busyKey(order, 'no-show'))" @click="$emit('no-show', order)">\u723d\u7ea6</button>
                <button class="button secondary" type="button" @click="$emit('logs', order)">\u65e5\u5fd7</button>
                <button class="button danger" type="button" :title="isInProgress(order) ? '\u9884\u7ea6\u8fdb\u884c\u4e2d\uff0c\u4e0d\u80fd\u5220\u9664' : ''" :disabled="$root.isSubmitting(busyKey(order, 'delete')) || isInProgress(order)" @click="$emit('delete-order', order)">\u5220\u9664</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
});

Vue.component("appointment-failure-table", {
  props: {
    logs: { type: Array, required: true },
    formatTime: { type: Function, required: true }
  },
  methods: {
    statusClass(item) {
      return item.status === "COMPENSATED" ? "warning" : "danger-soft";
    }
  },
  template: `
    <div>
      <empty-state v-if="logs.length === 0" title="\u6682\u65e0\u9884\u7ea6\u5f02\u5e38" description="\u5f02\u6b65\u8865\u507f\u6216\u6b7b\u4fe1\u8bb0\u5f55\u4f1a\u663e\u793a\u5728\u6b64\u5904\u3002"></empty-state>
      <div v-else class="table-wrap">
        <table class="appointment-table">
          <thead><tr><th>\u7c7b\u578b</th><th>\u5bf9\u8c61</th><th>\u72b6\u6001</th><th>\u539f\u56e0</th><th>\u65f6\u95f4</th></tr></thead>
          <tbody>
            <tr v-for="item in logs" :key="item.id">
              <td>{{ item.failureType }}</td>
              <td>
                <strong>{{ item.orderId || item.eventId || '-' }}</strong>
                <small>\u7528\u6237 {{ item.userId || '-' }} / \u65f6\u6bb5 {{ item.slotId || '-' }}</small>
              </td>
              <td><span class="badge" :class="statusClass(item)">{{ item.status }}</span></td>
              <td>{{ item.reason || '-' }}</td>
              <td>{{ formatTime(item.createTime) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
});

Vue.component("logs-table", {
  props: {
    logs: { type: Array, required: true },
    formatTime: { type: Function, required: true }
  },
  template: `
    <div>
      <empty-state v-if="logs.length === 0" title="\u6682\u65e0\u64cd\u4f5c\u65e5\u5fd7" description="\u7ba1\u7406\u64cd\u4f5c\u4ea7\u751f\u540e\u4f1a\u5728\u6b64\u5904\u8bb0\u5f55\u3002"></empty-state>
      <div v-else class="table-wrap">
        <table>
          <thead><tr><th>\u6a21\u5757</th><th>\u64cd\u4f5c</th><th>\u4e1a\u52a1</th><th>\u72b6\u6001\u53d8\u66f4</th><th>\u64cd\u4f5c\u4eba</th><th>\u5907\u6ce8\u6458\u8981</th><th>\u65f6\u95f4</th><th>\u7ed3\u679c</th></tr></thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id">
              <td>{{ log.module }}</td>
              <td>{{ log.operation }}</td>
              <td>{{ log.businessType ? (log.businessType + ' #' + log.businessId) : '-' }}</td>
              <td>{{ log.beforeStatus || '-' }} -> {{ log.afterStatus || '-' }}</td>
              <td>{{ log.userId || '-' }} / {{ log.userRole || '-' }}</td>
              <td>{{ log.remarkSummary || '-' }}</td>
              <td>{{ formatTime(log.createTime) }}</td>
              <td><span class="badge" :class="log.success ? 'success' : 'danger-soft'">{{ log.success ? '\u6210\u529f' : '\u5931\u8d25' }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `
});

Vue.component("chat-message", {
  props: {
    message: { type: Object, required: true },
    canManageKnowledge: { type: Boolean, default: false }
  },
  computed: {
    blocks() {
      return String(this.message.text || "")
        .split(/\n{2,}/)
        .map(block => block.trim())
        .filter(Boolean)
        .map(block => {
          const heading = block.match(/^#{1,3}\s+(.+)$/);
          return heading
            ? { type: "heading", text: heading[1] }
            : { type: "paragraph", text: block };
        });
    },
    sources() {
      return Array.isArray(this.message.sources) ? this.message.sources : [];
    },
    businessCards() {
      return Array.isArray(this.message.businessCards) ? this.message.businessCards : [];
    },
    actionDrafts() {
      return Array.isArray(this.message.actionDrafts) ? this.message.actionDrafts : [];
    },
    hasStructuredMeta() {
      return this.message.role === "assistant" && (
        this.message.confidence != null ||
        this.sources.length > 0 ||
        this.businessCards.length > 0 ||
        this.actionDrafts.length > 0 ||
        this.message.fallbackReason
      );
    },
    lowConfidence() {
      return Number(this.message.confidence || 0) > 0 && Number(this.message.confidence || 0) < 0.55;
    },
    fallbackText() {
      const map = {
        KNOWLEDGE_NOT_SYNCED: "\u77e5\u8bc6\u5e93\u672a\u540c\u6b65",
        NO_SOURCE: "\u6765\u6e90\u4e0d\u8db3",
        PERMISSION_DENIED: "\u65e0\u6743\u67e5\u770b",
        TOOL_UNAVAILABLE: "\u4e1a\u52a1\u67e5\u8be2\u4e0d\u53ef\u7528",
        AGENT_UNAVAILABLE: "\u52a9\u624b\u6682\u65f6\u4e0d\u53ef\u7528"
      };
      return map[this.message.fallbackReason] || this.message.fallbackReason || "";
    }
  },
  methods: {
    confidenceText(value) {
      if (value == null || value === "") {
        return "";
      }
      return Math.round(Number(value) * 100) + "%";
    },
    sourceTitle(source) {
      return source.title || source.name || source.module || source.type || "\u6765\u6e90";
    },
    sourceTypeText(type) {
      return ({
        knowledge: "\u77e5\u8bc6",
        service_point: "\u670d\u52a1\u70b9",
        service_category: "\u670d\u52a1\u5206\u7c7b",
        ticket: "\u5de5\u5355",
        appointment: "\u9884\u7ea6",
        inbox: "\u901a\u77e5",
        admin_log: "\u540e\u53f0\u65e5\u5fd7",
        business: "\u4e1a\u52a1"
      })[type] || type || "\u6765\u6e90";
    },
    sourceSnippet(source) {
      const parts = [
        source.snippet,
        source.statusText ? "\u72b6\u6001\uff1a" + source.statusText : "",
        source.address ? "\u5730\u5740\uff1a" + source.address : "",
        source.openHours ? "\u5f00\u653e\u65f6\u95f4\uff1a" + source.openHours : "",
        source.startTime || source.endTime ? "\u65f6\u95f4\uff1a" + (source.startTime || "-") + " - " + (source.endTime || "-") : "",
        source.operation ? "\u64cd\u4f5c\uff1a" + source.operation : ""
      ].filter(Boolean);
      return parts.join("\n") || "-";
    },
    cardTitle(card) {
      return card.title || card.name || card.servicePointName || card.slotTitle || card.module || card.failureType || (this.cardTypeText(card.type) + " #" + (card.id || card.messageId || "-"));
    },
    cardTypeText(type) {
      return this.sourceTypeText(type);
    },
    cardRows(card) {
      const rows = [
        ["ID", card.id || card.messageId || card.orderId || "-"],
        ["\u72b6\u6001", card.statusText || card.status || card.readStatus || "-"],
        ["\u670d\u52a1\u70b9", card.servicePointName || card.name || card.servicePointId || "-"],
        ["\u65f6\u95f4", card.startTime || card.createTime || "-"],
        ["\u7ed3\u675f\u65f6\u95f4", card.endTime || card.finishTime || "-"],
        ["\u9644\u4ef6", card.attachmentName || "-"]
      ];
      return rows.filter(row => row[1] !== "-" && row[1] != null && row[1] !== "");
    },
    canOpenCard(card) {
      return ["ticket", "appointment", "service_point", "inbox", "admin_log", "business"].includes(card.type || "business");
    },
    draftTypeText(type) {
      return ({
        create_ticket_draft: "\u5de5\u5355\u8349\u7a3f",
        appointment_query_draft: "\u9884\u7ea6\u8349\u7a3f",
        reply_ticket_draft: "\u56de\u590d\u8349\u7a3f"
      })[type] || "\u52a8\u4f5c\u8349\u7a3f";
    },
    draftTitle(draft) {
      return draft.title || this.draftTypeText(draft.type);
    },
    draftPayload(draft) {
      return draft && draft.payload && typeof draft.payload === "object" ? draft.payload : {};
    },
    draftRows(draft) {
      const payload = this.draftPayload(draft);
      const rows = [
        ["\u5de5\u5355\u6807\u9898", payload.title],
        ["\u5de5\u5355\u5185\u5bb9", payload.content],
        ["\u670d\u52a1\u70b9", payload.servicePointName || payload.servicePointId],
        ["\u9644\u4ef6\u63d0\u793a", payload.attachmentHint],
        ["\u65e5\u671f\u8303\u56f4", payload.dateRange],
        ["\u65f6\u6bb5\u6761\u4ef6", payload.slotFilter],
        ["\u5173\u8054\u5de5\u5355", payload.ticketId],
        ["\u56de\u590d\u5185\u5bb9", payload.replyContent]
      ];
      return rows.filter(row => row[1] !== "-" && row[1] != null && row[1] !== "");
    }
  },
  template: `
    <div class="chat-message" :class="message.role">
      <template v-for="(block, index) in blocks">
        <strong v-if="block.type === 'heading'" :key="'h' + index" class="chat-heading">{{ block.text }}</strong>
        <p v-else :key="'p' + index">{{ block.text }}</p>
      </template>
      <div v-if="message.role === 'assistant error' && message.retryQuestion" class="chat-message-actions">
        <button class="button secondary" type="button" @click="$emit('retry', message.retryQuestion)">\u91cd\u8bd5</button>
      </div>
      <div v-if="hasStructuredMeta" class="ai-structured-block">
        <div class="ai-answer-meta">
          <span v-if="message.confidence != null" class="badge" :class="{warning: lowConfidence}">\u7f6e\u4fe1\u5ea6 {{ confidenceText(message.confidence) }}</span>
          <span v-if="fallbackText" class="badge danger-soft">{{ fallbackText }}</span>
          <span v-if="lowConfidence" class="badge warning">\u6765\u6e90\u4e0d\u8db3</span>
          <button
            v-if="message.fallbackReason === 'KNOWLEDGE_NOT_SYNCED' && canManageKnowledge"
            class="button secondary"
            type="button"
            @click="$emit('open-knowledge')"
          >\u77e5\u8bc6\u5e93</button>
        </div>
        <div v-if="sources.length > 0" class="ai-source-list">
          <details v-for="(source, index) in sources" :key="'source-' + index" class="ai-source-item">
            <summary>
              <span>{{ sourceTitle(source) }}</span>
              <small>{{ sourceTypeText(source.type) }}</small>
            </summary>
            <p>{{ sourceSnippet(source) }}</p>
          </details>
        </div>
        <div v-if="businessCards.length > 0" class="ai-card-list">
          <article v-for="(card, index) in businessCards" :key="'card-' + index" class="ai-business-card">
            <header>
              <div>
                <strong>{{ cardTitle(card) }}</strong>
                <small>{{ cardTypeText(card.type) }}</small>
              </div>
              <span v-if="card.statusText || card.status" class="badge">{{ card.statusText || card.status }}</span>
            </header>
            <dl>
              <div v-for="row in cardRows(card)" :key="row[0]">
                <dt>{{ row[0] }}</dt>
                <dd>{{ row[1] }}</dd>
              </div>
            </dl>
            <footer>
              <button v-if="canOpenCard(card)" class="button secondary" type="button" @click="$emit('detail', card)">\u8be6\u60c5</button>
            </footer>
          </article>
        </div>
        <div v-if="actionDrafts.length > 0" class="ai-draft-list">
          <article v-for="(draft, index) in actionDrafts" :key="'draft-' + index" class="ai-action-draft">
            <header>
              <div>
                <strong>{{ draftTitle(draft) }}</strong>
                <small>{{ draftTypeText(draft.type) }}</small>
              </div>
            </header>
            <p v-if="draft.summary">{{ draft.summary }}</p>
            <dl>
              <div v-for="row in draftRows(draft)" :key="row[0]">
                <dt>{{ row[0] }}</dt>
                <dd>{{ row[1] }}</dd>
              </div>
            </dl>
            <footer>
              <button class="button secondary" type="button" @click="$emit('draft-action', draft)">\u53bb\u529e\u7406</button>
            </footer>
          </article>
        </div>
      </div>
    </div>
  `
});

Vue.component("ticket-detail", {
  props: {
    ticket: { type: [Object, Array], required: true },
    span: { type: String, default: "" },
    embedded: { type: Boolean, default: false },
    allowStudentReply: { type: Boolean, default: true },
    replyBusy: { type: Boolean, default: false },
    replyUploadBusy: { type: Boolean, default: false },
    replyAttachment: { type: Object, default: () => ({}) },
    replyAttachmentError: { type: String, default: "" },
    replyDraft: { type: Object, default: null }
  },
  data() {
    return {
      replyContent: "",
      replyError: ""
    };
  },
  computed: {
    record() {
      return this.ticket && this.ticket.ticket ? this.ticket.ticket : this.ticket;
    },
    comments() {
      return this.ticket && Array.isArray(this.ticket.comments) ? this.ticket.comments : [];
    },
    containerTag() {
      return this.embedded ? "div" : "section-panel";
    },
    containerProps() {
      return this.embedded
        ? { class: "ticket-detail-embedded" }
        : { span: this.span, title: "\u5de5\u5355\u8be6\u60c5", eyebrow: "\u8be6\u7ec6\u4fe1\u606f" };
    },
    statusText() {
      const status = Number(this.record.status);
      return ({ 0: "\u5f85\u53d7\u7406", 1: "\u5df2\u53d7\u7406", 2: "\u5904\u7406\u4e2d", 3: "\u5df2\u5b8c\u6210", 4: "\u5df2\u5173\u95ed", 5: "\u5df2\u62d2\u7edd" })[status] || "\u672a\u77e5\u72b6\u6001";
    },
    canStudentReply() {
      const status = Number(this.record.status);
      return this.allowStudentReply
        && Number(this.record.studentReplyRequired || 0) === 1
        && Number(this.record.adminDeleted || 0) !== 1
        && ![3, 4, 5].includes(status);
    },
    currentProgress() {
      if (Number(this.record.adminDeleted || 0) === 1) {
        return "\u5de5\u5355\u5df2\u88ab\u7ba1\u7406\u5458\u5220\u9664\uff0c\u4e0d\u518d\u7ee7\u7eed\u5904\u7406\u3002";
      }
      const status = Number(this.record.status);
      return ({
        0: "\u5de5\u5355\u5df2\u63d0\u4ea4\uff0c\u6b63\u5728\u7b49\u5f85\u7f51\u70b9\u6216\u5e73\u53f0\u7ba1\u7406\u5458\u53d7\u7406\u3002",
        1: "\u5de5\u5355\u5df2\u53d7\u7406\uff0c\u670d\u52a1\u4eba\u5458\u5df2\u7ecf\u5f00\u59cb\u8ddf\u8fdb\u3002",
        2: "\u5de5\u5355\u6b63\u5728\u5904\u7406\u4e2d\uff0c\u8bf7\u5173\u6ce8\u670d\u52a1\u4eba\u5458\u7684\u8fdb\u5ea6\u53cd\u9988\u3002",
        3: "\u5de5\u5355\u5df2\u5b8c\u6210\uff0c\u53ef\u4ee5\u6838\u5bf9\u5904\u7406\u7ed3\u679c\u5e76\u8fdb\u884c\u8bc4\u4ef7\u3002",
        4: "\u5de5\u5355\u5df2\u5173\u95ed\uff0c\u4e0d\u4f1a\u518d\u7ee7\u7eed\u5904\u7406\u3002",
        5: "\u5de5\u5355\u5df2\u88ab\u62d2\u7edd\uff0c\u53ef\u6839\u636e\u8bf4\u660e\u8865\u5145\u6750\u6599\u540e\u91cd\u65b0\u63d0\u4ea4\u3002"
      })[status] || "\u6682\u65e0\u8fdb\u5ea6\u4fe1\u606f\u3002";
    },
    nextAction() {
      if (Number(this.record.adminDeleted || 0) === 1) {
        return "\u5982\u4ecd\u9700\u8981\u5904\u7406\uff0c\u8bf7\u65b0\u5efa\u4e00\u5f20\u5de5\u5355\uff1b\u8be5\u8bb0\u5f55\u53ef\u4ee5\u5728\u5217\u8868\u4e2d\u5220\u9664\u3002";
      }
      const status = Number(this.record.status);
      return ({
        0: "\u5efa\u8bae\u5148\u7b49\u5f85\u53d7\u7406\u3002\u5982\u957f\u65f6\u95f4\u65e0\u54cd\u5e94\uff0c\u53ef\u6839\u636e\u8054\u7cfb\u65b9\u5f0f\u8054\u7cfb\u670d\u52a1\u4eba\u5458\u3002",
        1: "\u8bf7\u4fdd\u6301\u7535\u8bdd\u7545\u901a\uff0c\u5e76\u6309\u9700\u914d\u5408\u670d\u52a1\u4eba\u5458\u786e\u8ba4\u73b0\u573a\u60c5\u51b5\u3002",
        2: "\u5efa\u8bae\u7b49\u5f85\u5904\u7406\u7ed3\u679c\u3002\u5982\u95ee\u9898\u53d8\u5316\uff0c\u53ef\u5728\u6c9f\u901a\u8bb0\u5f55\u4e2d\u8865\u5145\u8bf4\u660e\u3002",
        3: "\u8bf7\u786e\u8ba4\u95ee\u9898\u662f\u5426\u89e3\u51b3\u3002\u82e5\u5df2\u89e3\u51b3\uff0c\u53ef\u8fdb\u884c\u8bc4\u4ef7\uff1b\u82e5\u672a\u89e3\u51b3\uff0c\u8bf7\u8054\u7cfb\u670d\u52a1\u4eba\u5458\u8865\u5145\u5904\u7406\u3002",
        4: "\u82e5\u95ee\u9898\u4ecd\u7136\u5b58\u5728\uff0c\u8bf7\u91cd\u65b0\u63d0\u4ea4\u5de5\u5355\u3002",
        5: "\u8bf7\u6839\u636e\u62d2\u7edd\u539f\u56e0\u8865\u5145\u4fe1\u606f\u540e\u91cd\u65b0\u63d0\u4ea4\u3002"
      })[status] || "\u8bf7\u7b49\u5f85\u670d\u52a1\u4eba\u5458\u8fdb\u4e00\u6b65\u5904\u7406\u3002";
    },
    timeline() {
      const rows = [
        { label: "\u5df2\u63d0\u4ea4", time: this.record.createTime, active: true },
        { label: "\u5df2\u53d7\u7406", time: this.record.acceptTime, active: Boolean(this.record.acceptTime) },
        { label: "\u5df2\u5b8c\u6210", time: this.record.finishTime, active: Boolean(this.record.finishTime) }
      ];
      if (this.record.deleteTime) {
        rows.push({ label: "\u7ba1\u7406\u5458\u5df2\u5220\u9664", time: this.record.deleteTime, active: true });
      }
      return rows;
    }
  },
  watch: {
    ticket: {
      deep: true,
      handler() {
        if (!this.canStudentReply) {
          this.replyContent = "";
          this.replyError = "";
        }
      }
    },
    replyDraft: {
      deep: true,
      immediate: true,
      handler(draft) {
        if (!draft || !this.canStudentReply) {
          return;
        }
        const ticketId = draft.ticketId || draft.id;
        if (ticketId && String(ticketId) !== String(this.record.id)) {
          return;
        }
        if (draft.replyContent) {
          this.replyContent = String(draft.replyContent).slice(0, 1024);
          this.replyError = "";
        }
      }
    }
  },
  methods: {
    formatTime(value) {
      return value ? String(value).replace("T", " ").slice(0, 19) : "\u6682\u65e0";
    },
    userTypeText(value) {
      return Number(value) === 1 ? "\u670d\u52a1\u4eba\u5458" : "\u5b66\u751f";
    },
    submitStudentReply() {
      const content = (this.replyContent || "").trim();
      if (!content) {
        this.replyError = "\u8bf7\u586b\u5199\u56de\u590d\u5185\u5bb9\u3002";
        return;
      }
      this.replyError = "";
      this.$emit("submit-reply", { ticket: this.record, content });
    }
  },
  template: `
    <component :is="containerTag" v-bind="containerProps">
      <div class="ticket-detail-view">
        <div class="ticket-detail-head">
          <div>
            <h3>{{ record.title || '\u672a\u547d\u540d\u5de5\u5355' }}</h3>
            <p>{{ record.content || '\u6682\u65e0\u95ee\u9898\u63cf\u8ff0' }}</p>
          </div>
          <span class="badge" :class="{'danger-soft': Number(record.adminDeleted || 0) === 1}">{{ Number(record.adminDeleted || 0) === 1 ? '\u5df2\u5220\u9664' : statusText }}</span>
        </div>

        <dl class="ticket-detail-grid">
          <div><dt>\u670d\u52a1\u7f51\u70b9</dt><dd>{{ record.servicePointId || '-' }}</dd></div>
          <div><dt>\u670d\u52a1\u5206\u7c7b</dt><dd>{{ record.categoryId || '-' }}</dd></div>
          <div><dt>\u8054\u7cfb\u65b9\u5f0f</dt><dd>{{ record.contactPhone || '-' }}</dd></div>
          <div><dt>\u8be6\u7ec6\u5730\u5740</dt><dd>{{ record.detailAddress || '-' }}</dd></div>
          <div>
            <dt>\u5b66\u751f\u9644\u4ef6</dt>
            <dd>
              <span>{{ record.attachmentName || '\u65e0' }}</span>
              <button v-if="record.attachmentUrl" class="button secondary attachment-button" type="button" @click="$emit('download-comment-attachment', record)">\u4e0b\u8f7d</button>
            </dd>
          </div>
          <div><dt>\u4f18\u5148\u7ea7</dt><dd>{{ record.priority || '-' }}</dd></div>
        </dl>

        <div class="ticket-guidance">
          <section>
            <strong>\u76ee\u524d\u8fdb\u5ea6</strong>
            <p>{{ currentProgress }}</p>
          </section>
          <section>
            <strong>\u4e0b\u4e00\u6b65\u5efa\u8bae</strong>
            <p>{{ nextAction }}</p>
          </section>
        </div>

        <section class="ticket-section">
          <h3>\u5386\u53f2\u8fdb\u5ea6</h3>
          <ol class="ticket-timeline">
            <li v-for="item in timeline" :key="item.label" :class="{active: item.active}">
              <strong>{{ item.label }}</strong>
              <span>{{ formatTime(item.time) }}</span>
            </li>
          </ol>
        </section>

        <section class="ticket-section" v-if="Number(record.adminDeleted || 0) === 1">
          <h3>\u5220\u9664\u8bf4\u660e</h3>
          <p class="danger-text">{{ record.deleteRemark || '\u7ba1\u7406\u5458\u672a\u586b\u5199\u8bf4\u660e' }}</p>
        </section>

        <section class="ticket-section" v-if="record.rating || record.evaluation">
          <h3>\u5904\u7406\u8bc4\u4ef7</h3>
          <p>{{ record.rating ? ('\u8bc4\u5206\uff1a' + record.rating + ' / 5') : '\u672a\u8bc4\u5206' }}</p>
          <p>{{ record.evaluation || '\u672a\u586b\u5199\u8bc4\u4ef7\u5185\u5bb9' }}</p>
        </section>

        <section class="ticket-section">
          <h3>\u6c9f\u901a\u8bb0\u5f55</h3>
          <form v-if="canStudentReply" class="ticket-reply-form" @submit.prevent="submitStudentReply">
            <div class="field" :class="{'has-error': replyError}">
              <label>\u56de\u590d\u670d\u52a1\u4eba\u5458 <span>\u5fc5\u586b</span></label>
              <textarea v-model.trim="replyContent" maxlength="1024" :disabled="replyBusy" placeholder="\u8bf7\u8865\u5145\u670d\u52a1\u4eba\u5458\u8981\u6c42\u7684\u4fe1\u606f"></textarea>
              <small v-if="replyError" class="field-error">{{ replyError }}</small>
              <small class="field-counter">{{ replyContent.length }}/1024</small>
            </div>
            <div class="field" :class="{'has-error': replyAttachmentError}">
              <label>\u9644\u4ef6</label>
              <div class="file-control">
                <input type="file" accept=".jpg,.jpeg,.png,.gif,.webp,.bmp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.zip,.rar,.7z" :disabled="replyBusy || replyUploadBusy" @change="$emit('reply-file-change', $event)">
                <button v-if="replyAttachment.attachmentName" class="button secondary" type="button" :disabled="replyBusy || replyUploadBusy" @click="$emit('remove-reply-attachment')">\u79fb\u9664</button>
              </div>
              <small v-if="replyAttachment.attachmentName" class="field-help">{{ replyUploadBusy ? '\u4e0a\u4f20\u4e2d...' : replyAttachment.attachmentName }}</small>
              <small v-if="replyAttachmentError" class="field-error">{{ replyAttachmentError }}</small>
            </div>
            <button class="button" type="submit" :disabled="replyBusy || replyUploadBusy">{{ replyBusy ? '\u63d0\u4ea4\u4e2d' : '\u63d0\u4ea4\u56de\u590d' }}</button>
          </form>
          <empty-state v-if="comments.length === 0" title="\u6682\u65e0\u6c9f\u901a\u8bb0\u5f55" description="\u6709\u8865\u5145\u8bf4\u660e\u6216\u670d\u52a1\u4eba\u5458\u53cd\u9988\u540e\u4f1a\u663e\u793a\u5728\u8fd9\u91cc\u3002"></empty-state>
          <div v-else class="ticket-comment-list">
            <article v-for="comment in comments" :key="comment.id" class="ticket-comment-item">
              <strong>{{ userTypeText(comment.userType) }} {{ comment.userId || '' }}</strong>
              <p>{{ comment.content }}</p>
              <button v-if="comment.attachmentUrl" class="button secondary" type="button" @click="$emit('download-comment-attachment', comment)">{{ comment.attachmentName || '\u4e0b\u8f7d\u9644\u4ef6' }}</button>
              <small>{{ formatTime(comment.createTime) }}</small>
            </article>
          </div>
        </section>
      </div>
    </component>
  `
});
