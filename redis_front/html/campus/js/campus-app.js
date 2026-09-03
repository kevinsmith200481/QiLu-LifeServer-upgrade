new Vue({
  el: "#app",
  mixins: [campusStateMixin],
  data() {
    return {
      activeView: "service",
      isDarkMode: false,
      token: campusApi.getToken(),
      currentUser: null,
      message: "\u51c6\u5907\u5c31\u7eea\u3002",
      messageType: "info",
      loading: false,
      loadingMap: {},
      submittingMap: {},
      debounceTimers: {},
      ticketActionMap: {},
      ticketUploadingAttachment: false,
      studentTicketReplyUploading: false,
      studentTicketReplyAttachment: {
        attachmentName: "",
        attachmentUrl: "",
        attachmentSize: null,
        attachmentType: ""
      },
      studentTicketReplyDraft: null,
      studentTicketReplyError: "",
      adminTicketReplyUploading: false,
      errorMap: {},
      ticketFormErrors: {},
      adminTicketReplyErrors: {},
      navItems: campusUiConfig.navItems,
      categories: [],
      selectedCategoryId: "",
      selectedPoint: null,
      servicePoints: [],
      servicePointPage: { current: 1, total: 0, size: 5 },
      commentSort: "latest",
      commentSortOptions: [
        { key: "latest", label: "\u6700\u65b0" },
        { key: "oldest", label: "\u6700\u65e9" },
        { key: "hot", label: "\u6700\u70ed" },
        { key: "admin", label: "\u53ea\u770b\u7ba1\u7406\u5458" }
      ],
      comments: [],
      commentPage: { nextCursor: null, nextCursorScore: null, offset: 0, hasMore: false, size: 10 },
      initialReplySize: 3,
      commentForm: { content: "" },
      replyMap: {},
      replyDrafts: {},
      replyTargets: {},
      replyDialog: {
        open: false,
        root: null,
        target: null,
        content: ""
      },
      ticketServicePoints: [],
      ticketCategories: campusUiConfig.ticketCategories,
      slots: [],
      myOrders: [],
      appointmentAction: "",
      selectedAppointmentOrderId: "",
      appointmentOrderFilter: "",
      ticketForm: {
        servicePointId: "",
        categoryId: "",
        title: "\u5bbf\u820d\u7ef4\u4fee\u7533\u8bf7",
        content: "\u5bbf\u820d\u7f51\u7edc\u4e0d\u7a33\u5b9a\uff0c\u9700\u8981\u5de5\u4f5c\u4eba\u5458\u4e0a\u95e8\u6392\u67e5\u3002",
        contactPhone: "",
        detailAddress: "",
        attachmentName: "",
        attachmentUrl: "",
        attachmentSize: null,
        attachmentType: ""
      },
      myTickets: [],
      myTicketPage: { current: 1, total: 0, size: 5 },
      selectedTicket: null,
      inboxMessages: [],
      inboxQuery: {
        messageType: "",
        readStatus: "",
        starStatus: "",
        monthKey: "",
        pageSize: 10,
        cursor: null
      },
      inboxPage: { nextCursor: null, hasMore: false },
      selectedInboxMap: {},
      selectedInboxMessage: null,
      selectedInboxAppointmentOrder: null,
      inboxUnreadCounts: { total: 0, typeCounts: {} },
      inboxSocketManager: null,
      inboxSocketState: "\u672a\u8fde\u63a5",
      inboxTypeOptions: [
        { value: "", label: "\u5168\u90e8\u7c7b\u578b" },
        { value: "SYSTEM_NOTICE", label: "\u7cfb\u7edf\u516c\u544a" },
        { value: "BUSINESS_REMINDER", label: "\u4e1a\u52a1\u63d0\u9192" },
        { value: "APPROVAL_NOTICE", label: "\u5ba1\u6279\u901a\u77e5" },
        { value: "EXCEPTION_ALERT", label: "\u5f02\u5e38\u544a\u8b66" },
        { value: "SITE_REPLY", label: "\u7ad9\u5185\u56de\u590d" }
      ],
      inboxSendForm: {
        messageType: "SYSTEM_NOTICE",
        targetType: "ALL",
        title: "",
        content: "",
        summary: "",
        businessType: "",
        businessId: "",
        userIdsText: "",
        rolesText: "student",
        expireTime: ""
      },
      inboxRevokeForm: {
        monthKey: "",
        messageId: ""
      },
      inboxRevokeDialogOpen: false,
      inboxRevokeMonthKey: "",
      inboxRevocableMessages: [],
      selectedRevokeInboxMap: {},
      lastInboxSent: null,
      aiQuestion: "\u56fe\u4e66\u9986\u9644\u8fd1\u54ea\u91cc\u53ef\u4ee5\u6253\u5370\u6750\u6599\uff1f",
      aiSessionId: null,
      aiSessions: [],
      aiSessionMenu: {
        open: false,
        x: 0,
        y: 0,
        session: null
      },
      chatMessages: [],
      aiDetailDialog: {
        open: false,
        title: "",
        type: "",
        item: null,
        loading: false,
        error: ""
      },
      adminModule: "tickets",
      adminModules: [
        { key: "tickets", label: "\u5de5\u5355\u7ba1\u7406", description: "\u53d7\u7406\u3001\u56de\u590d\u3001\u5b8c\u6210\u4e0e\u5173\u95ed" },
        { key: "inbox", label: "\u6536\u4ef6\u7bb1\u6cbb\u7406", description: "\u53d1\u9001\u6d88\u606f\u3001\u64a4\u56de\u901a\u77e5" },
        { key: "orders", label: "\u9884\u7ea6\u8ba2\u5355", description: "\u5b8c\u6210\u6216\u6807\u8bb0\u723d\u7ea6" },
        { key: "failures", label: "\u9884\u7ea6\u5f02\u5e38", description: "\u67e5\u770b\u8865\u507f\u548c\u6b7b\u4fe1\u8bb0\u5f55" },
        { key: "points", label: "\u670d\u52a1\u70b9", description: "\u65b0\u589e\u3001\u5ba1\u6838\u3001\u542f\u505c\u7528" },
        { key: "slots", label: "\u9884\u7ea6\u540d\u989d", description: "\u53d1\u5e03\u3001\u5f00\u542f\u3001\u5173\u95ed\u540d\u989d" },
        { key: "knowledge", label: "\u77e5\u8bc6\u5e93", description: "\u7ef4\u62a4\u667a\u80fd\u52a9\u624b\u77e5\u8bc6" },
        { key: "aiTrace", label: "AI \u8ffd\u8e2a", description: "\u67e5\u770b\u667a\u80fd\u52a9\u624b\u94fe\u8def\u548c\u7ed3\u6784\u5316\u8f93\u51fa" },
        { key: "logs", label: "\u64cd\u4f5c\u65e5\u5fd7", description: "\u67e5\u770b\u7ba1\u7406\u64cd\u4f5c\u8bb0\u5f55" }
      ],
      adminTickets: [],
      adminSelectedTicket: null,
      adminTicketPage: { current: 1, total: 0, size: 5 },
      adminTicketFilters: {
        status: "",
        servicePointId: "",
        requester: "",
        startTime: "",
        endTime: "",
        sortOrder: "desc",
        studentReplyRequired: ""
      },
      adminOrders: [],
      adminOrderPage: { current: 1, total: 0, size: 5 },
      adminOrderStats: { pending: 0, today: 0, finished: 0, abnormal: 0 },
      adminOrderFilters: {
        servicePointId: "",
        status: "",
        userId: "",
        startTime: "",
        endTime: ""
      },
      adminAppointmentActionDialog: {
        open: false,
        action: "",
        order: null,
        remark: "",
        internalRemark: ""
      },
      appointmentFailureLogs: [],
      appointmentFailureLogPage: { current: 1, total: 0, size: 5 },
      appointmentFailureFilters: {
        failureType: "",
        status: ""
      },
      adminServicePoints: [],
      adminServicePointPage: { current: 1, total: 0, size: 5 },
      adminTicketReplyDialog: {
        open: false,
        ticket: null,
        remark: "",
        attachmentName: "",
        attachmentUrl: "",
        attachmentSize: null,
        attachmentType: "",
        needStudentReply: false
      },
      deleteTicketTarget: null,
      deleteTicketRemark: "",
      reviewServicePoint: null,
      editingServicePoint: null,
      editServicePointForm: null,
      adminSlots: [],
      adminKnowledge: [],
      selectedKnowledgeDetail: null,
      servicePointForm: {
        name: "",
        categoryId: "",
        area: "",
        address: "",
        x: 117.1201,
        y: 36.6812,
        openHours: "08:30-18:00",
        phone: "",
        description: "",
        status: 1,
        score: 45,
        serviceCount: 0
      },
      slotForm: {
        servicePointId: "",
        title: "\u6821\u56ED\u670D\u52A1\u9884\u7EA6",
        description: "",
        totalQuota: 20,
        availableQuota: 20,
        startTime: "",
        endTime: "",
        status: 1
      },
      knowledgeForm: {
        title: "",
        content: "",
        category: "\u6821\u56ed\u670d\u52a1",
        source: "\u7ba1\u7406\u540e\u53f0",
        status: 1
      },
      operationLogs: [],
      operationLogFilters: {
        appointmentOrderId: ""
      },
      adminAiTraces: [],
      adminAiTracePage: { current: 1, total: 0, size: 10 },
      adminAiTraceMetrics: {
        totalRecent: 0,
        fallbackCount: 0,
        permissionDeniedCount: 0,
        noSourceCount: 0,
        sourceBackedCount: 0,
        averageConfidence: null,
        latestTraceId: "",
        intents: {}
      },
      selectedAiTrace: null
    };
  },
  computed: {
    currentTitle() {
      return campusUiConfig.titleMap[this.activeView] || "\u6821\u56ed\u670d\u52a1\u6f14\u793a";
    },
    currentNote() {
      return campusUiConfig.noteMap[this.activeView] || "\u9762\u5411\u6821\u56ed\u670d\u52a1\u7684\u5de5\u4f5c\u53f0\u3002";
    },
    themeToggleText() {
      return this.isDarkMode ? "\u4eae\u8272\u6a21\u5f0f" : "\u6df1\u8272\u6a21\u5f0f";
    },
    themeToggleTitle() {
      return this.isDarkMode ? "\u5207\u6362\u5230\u4eae\u8272\u6a21\u5f0f" : "\u5207\u6362\u5230\u6df1\u8272\u6a21\u5f0f";
    },
    visibleNavItems() {
      return this.navItems.filter(item => item.key !== "admin" || this.canAccessAdminView());
    },
    selectedInboxIds() {
      return Object.keys(this.selectedInboxMap)
        .filter(key => this.selectedInboxMap[key])
        .map(key => Number(key));
    },
    selectedRevokeInboxIds() {
      return Object.keys(this.selectedRevokeInboxMap)
        .filter(key => this.selectedRevokeInboxMap[key])
        .map(key => Number(key));
    },
    filteredMyOrders() {
      if (this.appointmentOrderFilter === "") {
        return this.myOrders;
      }
      return this.myOrders.filter(order => String(order.status) === String(this.appointmentOrderFilter));
    },
    appointmentOrderStats() {
      const stats = { total: this.myOrders.length, reserved: 0, finished: 0, abnormal: 0 };
      this.myOrders.forEach(order => {
        const status = Number(order.status);
        if (status === 1) stats.reserved += 1;
        if (status === 3) stats.finished += 1;
        if (status === 2 || status === 4 || status === 5) stats.abnormal += 1;
      });
      return stats;
    },
    currentAdminModule() {
      return this.adminModules.find(item => item.key === this.adminModule) || this.adminModules[0];
    }
  },
  watch: {
    selectedCategoryId() {
      this.servicePointPage.current = 1;
      this.debounce("service-filter", () => this.loadServicePoints(), 320);
    }
  },
  async mounted() {
    this.initTheme();
    if (!campusApi.getToken()) {
      this.redirectToLogin();
      return;
    }
    await this.loadCurrentUser();
    await this.loadInboxUnreadCounts();
    this.connectInboxWebSocket();
    await this.loadCategories();
    if (!this.selectedCategoryId) {
      await this.loadServicePoints();
    }
  },
  beforeDestroy() {
    this.destroyInboxWebSocket();
  },
  methods: {
    initTheme() {
      const currentTheme = document.documentElement.getAttribute("data-theme");
      let theme = currentTheme || "light";
      try {
        theme = localStorage.getItem("campus-theme") || theme;
      } catch (error) {
        theme = currentTheme || "light";
      }
      this.applyTheme(theme === "dark", false);
    },
    toggleTheme() {
      this.applyTheme(!this.isDarkMode, true);
    },
    applyTheme(isDarkMode, persist) {
      this.isDarkMode = Boolean(isDarkMode);
      const theme = this.isDarkMode ? "dark" : "light";
      document.documentElement.setAttribute("data-theme", theme);
      if (persist === false) {
        return;
      }
      try {
        localStorage.setItem("campus-theme", theme);
      } catch (error) {
        // Browsers can block localStorage in private or restricted modes.
      }
    },
    setView(view) {
      if (view === "admin" && !this.canAccessAdminView()) {
        this.activeView = "service";
        this.notify("\u5f53\u524d\u8d26\u53f7\u6ca1\u6709\u8bbf\u95ee\u7ba1\u7406\u540e\u53f0\u7684\u6743\u9650\u3002", "error");
        return;
      }
      this.activeView = view;
      if (view === "appointment") {
        this.loadMyOrders();
      }
      if (view === "board" && this.selectedPoint) {
        this.loadComments(true);
      }
      if (view === "ticket") {
        this.loadTicketServicePoints();
        this.loadMyTickets();
      }
      if (view === "inbox") {
        this.loadInboxUnreadCounts();
        this.loadInboxMessages(true);
      }
      if (view === "admin") {
        this.loadAdminModule(this.adminModule);
      }
      if (view === "ai") {
        this.loadAiSessions();
      }
    },
    busyTicketAction(id) {
      return this.ticketActionMap[id] || "";
    },
    studentTicketReplyBusy() {
      const ticket = this.selectedTicket && this.selectedTicket.ticket ? this.selectedTicket.ticket : this.selectedTicket;
      return ticket && ticket.id ? this.isSubmitting("ticket:" + ticket.id + ":studentReply") : false;
    },
    isAdminUser() {
      return this.currentUser && this.currentUser.role === "admin";
    },
    canAccessAdminView() {
      return this.currentUser && (this.currentUser.role === "admin" || this.currentUser.role === "manager");
    },
    async saveToken() {
      if (!this.token.trim()) {
        this.notify("\u8bf7\u5148\u8f93\u5165\u6709\u6548\u767b\u5f55\u51ed\u8bc1\u3002", "error");
        return;
      }
      await this.withSubmit("saveToken", async () => {
        campusApi.saveToken(this.token.trim());
        this.notify("\u767b\u5f55\u51ed\u8bc1\u5df2\u4fdd\u5b58\uff0c\u6b63\u5728\u5237\u65b0\u5f53\u524d\u89c6\u56fe\u3002");
        await this.refreshActiveView();
      });
    },
    async loadCurrentUser() {
      await this.callApi(async () => {
        this.currentUser = await campusApi.loadMe();
      }, null, "currentUser");
    },
    async logout() {
      await this.withSubmit("logout", async () => {
        this.closeInboxWebSocket("logout");
        try {
          await campusApi.logout();
        } finally {
          campusApi.clearToken();
          this.currentUser = null;
          location.href = "/campus/login.html";
        }
      });
    },
    redirectToLogin() {
      const redirect = encodeURIComponent(location.pathname + location.search + location.hash);
      location.href = "/campus/login.html?redirect=" + redirect;
    },
    async refreshActiveView() {
      if (this.activeView === "service") {
        await this.loadCategories();
        await this.loadServicePoints();
        return;
      }
      if (this.activeView === "appointment") {
        await this.loadMyOrders();
        if (this.selectedPoint) {
          await this.loadSlots(this.selectedPoint.id);
        }
        return;
      }
      if (this.activeView === "board") {
        await this.loadComments(true);
        return;
      }
      if (this.activeView === "ticket") {
        await this.loadTicketServicePoints();
        await this.loadMyTickets();
        return;
      }
      if (this.activeView === "inbox") {
        await this.loadInboxUnreadCounts();
        await this.loadInboxMessages(true);
        return;
      }
      if (this.activeView === "admin") {
        await this.loadAdminModule(this.adminModule);
        return;
      }
      if (this.activeView === "ai") {
        await this.loadAiSessions();
      }
    },
    async callApi(action, successText, key) {
      this.setLoading(key || "global", true);
      this.setPanelError(key, "");
      try {
        const result = await action();
        if (successText) {
          this.notify(successText);
        }
        return result;
      } catch (error) {
        const message = String(error);
        if (key && !key.includes(":")) {
          this.setPanelError(key, message);
        }
        this.notify(message, "error");
        throw error;
      } finally {
        this.setLoading(key || "global", false);
      }
    },
    wait(ms) {
      return new Promise(resolve => setTimeout(resolve, ms));
    },
    async loadCategories() {
      const body = await this.callApi(() => axios.get("/service-category/list"), null, "categories");
      this.categories = campusApi.unwrapRecords(body);
    },
    async loadServicePoints() {
      const body = await this.callApi(async () => {
        if (this.selectedCategoryId) {
          return axios.get("/service-point/of/category", {
            params: { categoryId: this.selectedCategoryId, current: this.servicePointPage.current }
          });
        }
        return axios.get("/service-point/of/name", { params: { current: this.servicePointPage.current } });
      }, "\u670d\u52a1\u7f51\u70b9\u5df2\u52a0\u8f7d\u3002", "servicePoints");
      this.servicePoints = campusApi.unwrapRecords(body);
      this.servicePointPage.total = Number(body.total || this.servicePoints.length || 0);
      if (!this.selectedPoint && this.servicePoints.length > 0) {
        this.selectPoint(this.servicePoints[0]);
      }
    },
    changePage(pageState, nextPage, loader) {
      const totalPages = this.pageCount(pageState);
      const normalized = Math.max(1, Math.min(Number(nextPage || 1), totalPages));
      if (pageState.current === normalized && totalPages > 0) {
        return;
      }
      pageState.current = normalized;
      loader();
    },
    pageCount(pageState) {
      const size = Number(pageState && pageState.size || 5);
      const total = Number(pageState && pageState.total || 0);
      return Math.max(1, Math.ceil(total / size));
    },
    async loadTicketServicePoints() {
      const body = await this.callApi(() => axios.get("/service-point/enabled"), null, "ticketServicePoints");
      this.ticketServicePoints = campusApi.unwrapRecords(body);
      if (!this.ticketForm.servicePointId && this.ticketServicePoints.length > 0) {
        this.handleTicketPointChange(this.ticketServicePoints[0].id);
      }
    },
    handleTicketPointChange(pointId) {
      this.ticketForm.servicePointId = pointId || "";
      const point = this.ticketServicePoints.find(item => String(item.id) === String(pointId));
      if (point && point.categoryId) {
        this.ticketForm.categoryId = point.categoryId;
      }
      this.validateTicketField("servicePointId");
      this.validateTicketField("categoryId");
    },
    async selectPoint(point) {
      this.selectedPoint = point;
      this.ticketForm.servicePointId = point.id;
      this.ticketForm.categoryId = point.categoryId || this.selectedCategoryId || "";
      await this.loadSlots(point.id);
    },
    async reservePoint(point) {
      this.setView("appointment");
      await this.selectPoint(point);
    },
    async openBoard(point) {
      this.selectedPoint = point;
      this.ticketForm.servicePointId = point.id;
      this.ticketForm.categoryId = point.categoryId || this.selectedCategoryId || "";
      this.activeView = "board";
      await this.loadComments(true);
    },
    resetCommentPage() {
      this.commentPage = { nextCursor: null, nextCursorScore: null, offset: 0, hasMore: false, size: 10 };
      this.replyMap = {};
      this.replyDrafts = {};
      this.replyTargets = {};
      this.replyDialog = { open: false, root: null, target: null, content: "" };
    },
    async changeCommentSort(sort) {
      if (this.commentSort === sort) {
        return;
      }
      this.commentSort = sort;
      await this.loadComments(true);
    },
    async loadComments(reset) {
      if (!this.selectedPoint || !this.selectedPoint.id) {
        this.comments = [];
        this.resetCommentPage();
        return;
      }
      if (reset) {
        this.comments = [];
        this.resetCommentPage();
      }
      const key = reset ? "comments" : "commentsMore";
      const stationId = this.selectedPoint.id;
      const params = { size: this.commentPage.size };
      let url = "/service-point/" + stationId + "/comments";
      if (this.commentSort === "hot") {
        url += "/hot";
        params.cursorScore = reset ? null : this.commentPage.nextCursorScore;
        params.offset = reset ? 0 : this.commentPage.offset;
      } else if (this.commentSort === "admin") {
        url += "/admin";
        params.cursor = reset ? null : this.commentPage.nextCursor;
      } else {
        params.sort = this.commentSort;
        params.cursor = reset ? null : this.commentPage.nextCursor;
      }
      const body = await this.callApi(() => axios.get(url, { params }), null, key);
      const page = body.data || {};
      const list = Array.isArray(page.list) ? page.list : [];
      this.comments = reset ? list : this.comments.concat(list);
      this.commentPage.nextCursor = page.nextCursor || null;
      this.commentPage.nextCursorScore = page.nextCursorScore == null ? null : page.nextCursorScore;
      this.commentPage.offset = Number(page.offset || 0);
      this.commentPage.hasMore = Boolean(page.hasMore);
      await this.loadInitialReplies(list);
    },
    async createFloorComment() {
      const content = (this.commentForm.content || "").trim();
      if (!this.selectedPoint || !this.selectedPoint.id) {
        this.notify("\u8bf7\u5148\u9009\u62e9\u670d\u52a1\u7f51\u70b9\u3002", "error");
        return;
      }
      if (!content) {
        this.notify("\u8bf7\u586b\u5199\u7559\u8a00\u5185\u5bb9\u3002", "error");
        return;
      }
      await this.withSubmit("comment:create", async () => {
        await this.callApi(
          () => axios.post("/service-point/" + this.selectedPoint.id + "/comments", { content }),
          "\u7559\u8a00\u5df2\u53d1\u5e03\u3002",
          "comment:create"
        );
        this.commentForm.content = "";
        this.commentSort = "latest";
        await this.loadComments(true);
      });
    },
    async loadInitialReplies(list) {
      const roots = (list || []).filter(comment => this.isRootComment(comment) && Number(comment.replyCount || 0) > 0);
      for (const comment of roots) {
        await this.loadReplies(comment.id, true, this.initialReplySize);
      }
    },
    async loadReplies(rootId, reset, size) {
      if (!this.selectedPoint || !rootId) {
        return;
      }
      const state = this.ensureReplyState(rootId);
      if (reset) {
        state.list = [];
        state.nextCursor = null;
        state.hasMore = false;
      }
      const body = await this.callApi(() => axios.get(
        "/service-point/" + this.selectedPoint.id + "/comments/" + rootId + "/replies",
        { params: { cursor: reset ? null : state.nextCursor, size: size || 10 } }
      ), null, "replies:" + rootId);
      const page = body.data || {};
      const list = Array.isArray(page.list) ? page.list : [];
      state.list = reset ? list : state.list.concat(list);
      state.nextCursor = page.nextCursor || null;
      state.hasMore = Boolean(page.hasMore);
      this.$set(this.replyMap, rootId, Object.assign({}, state));
    },
    async likeComment(comment) {
      if (!this.selectedPoint || !comment || !comment.id) {
        return;
      }
      const body = await this.callApi(
        () => axios.put("/service-point/" + this.selectedPoint.id + "/comments/" + comment.id + "/like"),
        null,
        "comment:" + comment.id + ":like"
      );
      const data = body.data || {};
      this.$set(comment, "likeCount", Number(data.likeCount || 0));
      this.$set(comment, "liked", Boolean(data.liked));
    },
    resolveRootComment(comment) {
      if (!comment) {
        return null;
      }
      if (this.isRootComment(comment)) {
        return comment;
      }
      return this.comments.find(item => Number(item.id) === Number(comment.parentId)) || comment;
    },
    openReplyDialog(rootComment, targetComment) {
      const root = this.resolveRootComment(rootComment);
      const target = targetComment || root;
      if (!root || !root.id || !target || !target.id) {
        return;
      }
      this.replyDialog = {
        open: true,
        root,
        target,
        content: ""
      };
    },
    closeReplyDialog() {
      if (this.isSubmitting(this.replySubmitKey())) {
        return;
      }
      this.replyDialog = { open: false, root: null, target: null, content: "" };
    },
    replySubmitKey() {
      return this.replyDialog && this.replyDialog.root
        ? "comment:" + this.replyDialog.root.id + ":reply"
        : "comment:reply";
    },
    replyDialogTargetText() {
      const target = this.replyDialog && this.replyDialog.target;
      if (!target) {
        return "\u56de\u590d\u7559\u8a00";
      }
      return "\u56de\u590d " + (target.userName || ("\u7528\u6237 " + target.userId));
    },
    async submitReplyDialog() {
      const root = this.replyDialog.root;
      const target = this.replyDialog.target || root;
      const content = (this.replyDialog.content || "").trim();
      if (!root || !root.id || !target || !target.id) {
        this.notify("\u8bf7\u5148\u9009\u62e9\u8981\u56de\u590d\u7684\u7559\u8a00\u3002", "error");
        return;
      }
      if (!content) {
        this.notify("\u8bf7\u586b\u5199\u56de\u590d\u5185\u5bb9\u3002", "error");
        return;
      }
      const key = this.replySubmitKey();
      await this.withSubmit(key, async () => {
        await this.callApi(
          () => axios.post("/service-point/" + this.selectedPoint.id + "/comments", {
            parentId: root.id,
            replyToCommentId: target.id,
            content
          }),
          "\u56de\u590d\u5df2\u53d1\u5e03\u3002",
          key
        );
        this.$set(root, "replyCount", Number(root.replyCount || 0) + 1);
        await this.loadReplies(root.id, true);
        this.replyDialog = { open: false, root: null, target: null, content: "" };
      });
    },
    async createReply(rootComment) {
      const content = this.replyDraft(rootComment.id).trim();
      if (!content) {
        this.notify("\u8bf7\u586b\u5199\u56de\u590d\u5185\u5bb9\u3002", "error");
        return;
      }
      const target = this.replyTargets[rootComment.id] || rootComment;
      await this.withSubmit("comment:" + rootComment.id + ":reply", async () => {
        await this.callApi(
          () => axios.post("/service-point/" + this.selectedPoint.id + "/comments", {
            parentId: rootComment.id,
            replyToCommentId: target.id,
            content
          }),
          "\u56de\u590d\u5df2\u53d1\u5e03\u3002",
          "comment:" + rootComment.id + ":reply"
        );
        this.$set(this.replyDrafts, rootComment.id, "");
        this.$set(this.replyTargets, rootComment.id, rootComment);
        this.$set(rootComment, "replyCount", Number(rootComment.replyCount || 0) + 1);
        await this.loadReplies(rootComment.id, true);
      });
    },
    async deleteComment(comment) {
      if (!this.selectedPoint || !comment || !comment.id) {
        return;
      }
      if (!window.confirm("\u786e\u8ba4\u5220\u9664\u8fd9\u6761\u7559\u8a00\u5417\uff1f")) {
        return;
      }
      await this.withSubmit("comment:" + comment.id + ":delete", async () => {
        await this.callApi(
          () => axios.delete("/service-point/" + this.selectedPoint.id + "/comments/" + comment.id),
          "\u7559\u8a00\u5df2\u5220\u9664\uff0c\u697c\u4e2d\u697c\u5c06\u5f02\u6b65\u6e05\u7406\u3002",
          "comment:" + comment.id + ":delete"
        );
        await this.loadComments(true);
      });
    },
    ensureReplyState(rootId) {
      if (!this.replyMap[rootId]) {
        this.$set(this.replyMap, rootId, { list: [], nextCursor: null, hasMore: false });
      }
      return this.replyMap[rootId];
    },
    replyState(rootId) {
      return this.ensureReplyState(rootId);
    },
    isReplyLoading(rootId) {
      return this.isLoading("replies:" + rootId);
    },
    replyDraft(rootId) {
      return this.replyDrafts[rootId] || "";
    },
    setReplyDraft(rootId, value) {
      this.$set(this.replyDrafts, rootId, value || "");
    },
    setReplyTarget(rootComment, targetComment) {
      this.$set(this.replyTargets, rootComment.id, targetComment || rootComment);
      this.notify("\u5df2\u5207\u6362\u56de\u590d\u5bf9\u8c61\u3002");
    },
    replyTargetText(rootComment) {
      const target = this.replyTargets[rootComment.id] || rootComment;
      return "\u56de\u590d " + (target.userName || ("\u7528\u6237 " + target.userId));
    },
    isRootComment(comment) {
      return !comment.parentId || Number(comment.parentId) === 0;
    },
    canDeleteComments() {
      return this.currentUser && (this.currentUser.role === "admin" || this.currentUser.role === "manager");
    },
    async loadSlots(servicePointId) {
      if (!servicePointId) {
        this.slots = [];
        return;
      }
      const body = await this.callApi(() => axios.get("/appointment-slot/of/service-point/" + servicePointId), null, "slots");
      this.slots = campusApi.unwrapRecords(body);
    },
    async reserveSlot(slot) {
      await this.withSubmit("reserve:" + slot.id, async () => {
        const body = await this.callApi(
          () => axios.post("/appointment-order/reserve/" + slot.id),
          "\u9884\u7ea6\u5df2\u63d0\u4ea4\uff0c\u8ba2\u5355\u5c06\u5f02\u6b65\u843d\u5e93\u3002",
          "reserveSlot"
        );
        const orderId = body && body.data;
        if (orderId) {
          const order = await this.waitForMyOrder(orderId);
          if (order) {
            this.upsertMyOrder(order);
            this.focusAppointmentOrder(order.id);
          }
        }
        await this.loadMyOrders();
        if (this.selectedPoint) {
          await this.loadSlots(this.selectedPoint.id);
        }
      });
    },
    async loadMyOrders() {
      const body = await this.callApi(() => axios.get("/appointment-order/mine"), null, "myOrders");
      this.myOrders = campusApi.unwrapRecords(body);
    },
    async loadMyOrderDetail(orderId) {
      if (!orderId) {
        return null;
      }
      const body = await this.callApi(() => axios.get("/appointment-order/" + orderId), null, "appointmentDetail");
      return body.data || null;
    },
    async waitForMyOrder(orderId) {
      for (let i = 0; i < 8; i += 1) {
        try {
          const body = await axios.get("/appointment-order/" + orderId);
          if (body && body.data) {
            return body.data;
          }
        } catch (error) {
          await this.wait(300);
        }
      }
      return null;
    },
    upsertMyOrder(order) {
      if (!order || !order.id) {
        return;
      }
      const index = this.myOrders.findIndex(item => String(item.id) === String(order.id));
      if (index >= 0) {
        this.$set(this.myOrders, index, order);
        return;
      }
      this.myOrders = [order].concat(this.myOrders);
    },
    focusAppointmentOrder(orderId) {
      this.selectedAppointmentOrderId = orderId || "";
      this.$nextTick(() => {
        const row = this.$el.querySelector("[data-order-id='" + orderId + "']");
        if (row && row.scrollIntoView) {
          row.scrollIntoView({ behavior: "smooth", block: "center" });
        }
      });
    },
    async cancelAppointmentOrder(order) {
      if (!order || !order.id) {
        return;
      }
      const orderId = String(order.id);
      await this.withSubmit("cancel:" + orderId, async () => {
        this.appointmentAction = "cancel:" + orderId;
        try {
          await this.callApi(
            () => axios.put("/appointment-order/cancel/" + orderId),
            "\u9884\u7ea6\u5df2\u53d6\u6d88\u3002",
            "cancelAppointment"
          );
          await this.loadMyOrders();
          if (this.selectedPoint) {
            await this.loadSlots(this.selectedPoint.id);
          }
          if (this.selectedInboxAppointmentOrder && String(this.selectedInboxAppointmentOrder.id) === orderId) {
            this.selectedInboxAppointmentOrder = await this.loadMyOrderDetail(orderId);
          }
        } finally {
          this.appointmentAction = "";
        }
      });
    },
    async deleteMyAppointmentOrder(order) {
      if (!order || !order.id) {
        return;
      }
      const orderId = String(order.id);
      if (!window.confirm("\u5220\u9664\u540e\u4e0d\u4f1a\u5728\u6211\u7684\u9884\u7ea6\u8bb0\u5f55\u4e2d\u663e\u793a\u3002\u786e\u8ba4\u5220\u9664\u9884\u7ea6 " + orderId + " \u5417\uff1f")) {
        return;
      }
      await this.withSubmit("delete:" + orderId, async () => {
        this.appointmentAction = "delete:" + orderId;
        try {
          await this.callApi(
            () => axios.delete("/appointment-order/" + orderId),
            "\u9884\u7ea6\u8bb0\u5f55\u5df2\u5220\u9664\u3002",
            "deleteAppointment"
          );
          if (String(this.selectedAppointmentOrderId) === orderId) {
            this.selectedAppointmentOrderId = "";
          }
          await this.loadMyOrders();
          if (this.selectedPoint) {
            await this.loadSlots(this.selectedPoint.id);
          }
        } finally {
          this.appointmentAction = "";
        }
      });
    },
    async createTicket() {
      if (!this.validateTicketForm()) {
        this.notify("\u8bf7\u5148\u4fee\u6b63\u5de5\u5355\u8868\u5355\u4e2d\u7684\u95ee\u9898\u3002", "error");
        return;
      }
      const payload = {
        servicePointId: this.ticketForm.servicePointId || null,
        categoryId: this.ticketForm.categoryId || null,
        contactPhone: this.ticketForm.contactPhone,
        detailAddress: this.ticketForm.detailAddress,
        attachmentName: this.ticketForm.attachmentName,
        attachmentUrl: this.ticketForm.attachmentUrl,
        attachmentSize: this.ticketForm.attachmentSize,
        attachmentType: this.ticketForm.attachmentType,
        title: this.ticketForm.title,
        content: this.ticketForm.content
      };
      await this.withSubmit("createTicket", async () => {
        await this.callApi(() => axios.post("/ticket", payload), "\u5de5\u5355\u5df2\u521b\u5efa\u3002", "createTicket");
        this.ticketFormErrors = {};
        this.$set(this.ticketForm, "title", "");
        this.$set(this.ticketForm, "content", "");
        this.$set(this.ticketForm, "contactPhone", "");
        this.$set(this.ticketForm, "detailAddress", "");
        this.clearTicketAttachment();
        this.myTicketPage.current = 1;
        await this.loadMyTickets();
      });
    },
    async handleTicketFileChange(event) {
      const input = event && event.target;
      const file = input && input.files && input.files[0];
      if (!file) {
        return;
      }
      const errors = Object.assign({}, this.ticketFormErrors);
      const allowed = /\.(jpg|jpeg|png|gif|webp|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|txt|zip|rar|7z)$/i;
      if (!allowed.test(file.name || "")) {
        errors.attachment = "\u4ec5\u652f\u6301\u56fe\u7247\u3001\u6587\u6863\u548c\u538b\u7f29\u5305\u9644\u4ef6\u3002";
        this.ticketFormErrors = errors;
        input.value = "";
        return;
      }
      if (file.size > 20 * 1024 * 1024) {
        errors.attachment = "\u9644\u4ef6\u4e0d\u80fd\u8d85\u8fc7 20MB\u3002";
        this.ticketFormErrors = errors;
        input.value = "";
        return;
      }
      delete errors.attachment;
      this.ticketFormErrors = errors;
      const formData = new FormData();
      formData.append("file", file);
      this.ticketUploadingAttachment = true;
      try {
        const body = await this.callApi(() => axios.post("/ticket/attachment", formData, {
          headers: { "Content-Type": "multipart/form-data" }
        }), "\u9644\u4ef6\u5df2\u4e0a\u4f20\u3002", "ticketAttachment");
        const attachment = body.data || {};
        this.$set(this.ticketForm, "attachmentName", attachment.name || file.name);
        this.$set(this.ticketForm, "attachmentUrl", attachment.url || "");
        this.$set(this.ticketForm, "attachmentSize", attachment.size || file.size);
        this.$set(this.ticketForm, "attachmentType", attachment.type || file.type || "");
      } catch (error) {
        errors.attachment = String(error || "\u9644\u4ef6\u4e0a\u4f20\u5931\u8d25\u3002");
        this.ticketFormErrors = errors;
      } finally {
        this.ticketUploadingAttachment = false;
        input.value = "";
      }
    },
    clearTicketAttachment() {
      this.$set(this.ticketForm, "attachmentName", "");
      this.$set(this.ticketForm, "attachmentUrl", "");
      this.$set(this.ticketForm, "attachmentSize", null);
      this.$set(this.ticketForm, "attachmentType", "");
      const errors = Object.assign({}, this.ticketFormErrors);
      delete errors.attachment;
      this.ticketFormErrors = errors;
    },
    async handleStudentTicketReplyFileChange(event) {
      const input = event && event.target;
      const file = input && input.files && input.files[0];
      if (!file) {
        return;
      }
      const allowed = /\.(jpg|jpeg|png|gif|webp|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|txt|zip|rar|7z)$/i;
      if (!allowed.test(file.name || "")) {
        this.studentTicketReplyError = "\u4ec5\u652f\u6301\u56fe\u7247\u3001\u6587\u6863\u548c\u538b\u7f29\u5305\u9644\u4ef6\u3002";
        input.value = "";
        return;
      }
      if (file.size > 20 * 1024 * 1024) {
        this.studentTicketReplyError = "\u9644\u4ef6\u4e0d\u80fd\u8d85\u8fc7 20MB\u3002";
        input.value = "";
        return;
      }
      this.studentTicketReplyError = "";
      const formData = new FormData();
      formData.append("file", file);
      this.studentTicketReplyUploading = true;
      try {
        const body = await this.callApi(() => axios.post("/ticket/attachment", formData, {
          headers: { "Content-Type": "multipart/form-data" }
        }), "\u9644\u4ef6\u5df2\u4e0a\u4f20\u3002", "studentTicketReplyAttachment");
        const attachment = body.data || {};
        this.studentTicketReplyAttachment = {
          attachmentName: attachment.name || file.name,
          attachmentUrl: attachment.url || "",
          attachmentSize: attachment.size || file.size,
          attachmentType: attachment.type || file.type || ""
        };
      } catch (error) {
        this.studentTicketReplyError = String(error || "\u9644\u4ef6\u4e0a\u4f20\u5931\u8d25\u3002");
      } finally {
        this.studentTicketReplyUploading = false;
        input.value = "";
      }
    },
    clearStudentTicketReplyAttachment() {
      this.studentTicketReplyAttachment = {
        attachmentName: "",
        attachmentUrl: "",
        attachmentSize: null,
        attachmentType: ""
      };
      this.studentTicketReplyError = "";
    },
    async downloadTicketAttachment(ticket) {
      if (!ticket || !ticket.attachmentUrl) {
        return;
      }
      const response = await fetch(campusApi.baseURL + ticket.attachmentUrl, {
        headers: { authorization: campusApi.getToken() }
      });
      if (!response.ok) {
        this.notify("\u9644\u4ef6\u4e0b\u8f7d\u5931\u8d25\u3002", "error");
        return;
      }
      const blob = await response.blob();
      const blobUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = blobUrl;
      link.download = ticket.attachmentName || "ticket-attachment";
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(blobUrl);
    },
    validateTicketField(field) {
      const errors = Object.assign({}, this.ticketFormErrors);
      const nextErrors = campusUiConfig.validateTicket(this.ticketForm, field);
      if (!field || field === "title") {
        nextErrors.title ? errors.title = nextErrors.title : delete errors.title;
      }
      if (!field || field === "content") {
        nextErrors.content ? errors.content = nextErrors.content : delete errors.content;
      }
      if (!field || field === "servicePointId") {
        nextErrors.servicePointId ? errors.servicePointId = nextErrors.servicePointId : delete errors.servicePointId;
      }
      if (!field || field === "categoryId") {
        nextErrors.categoryId ? errors.categoryId = nextErrors.categoryId : delete errors.categoryId;
      }
      if (!field || field === "contactPhone") {
        nextErrors.contactPhone ? errors.contactPhone = nextErrors.contactPhone : delete errors.contactPhone;
      }
      if (!field || field === "detailAddress") {
        nextErrors.detailAddress ? errors.detailAddress = nextErrors.detailAddress : delete errors.detailAddress;
      }
      this.ticketFormErrors = errors;
      return Object.keys(errors).length === 0;
    },
    validateTicketForm() {
      return this.validateTicketField();
    },
    async loadMyTickets() {
      const body = await this.callApi(() => axios.get("/ticket/mine", { params: { current: this.myTicketPage.current } }), null, "myTickets");
      this.myTickets = campusApi.unwrapRecords(body);
      this.myTicketPage.total = Number(body.total || this.myTickets.length || 0);
    },
    async hideMyTicket(ticket) {
      if (!ticket || !ticket.id) {
        return;
      }
      if (!window.confirm("\u786e\u5b9a\u4ece\u6211\u7684\u5de5\u5355\u4e2d\u79fb\u9664\u8fd9\u5f20\u5de5\u5355\u5417\uff1f")) {
        return;
      }
      await this.withSubmit("ticket:" + ticket.id + ":hide", async () => {
        await this.callApi(() => axios.delete("/ticket/" + ticket.id), "\u5de5\u5355\u5df2\u4ece\u6211\u7684\u5217\u8868\u79fb\u9664\u3002", "ticket:" + ticket.id + ":hide");
        if (this.myTickets.length === 1 && this.myTicketPage.current > 1) {
          this.myTicketPage.current -= 1;
        }
        await this.loadMyTickets();
      });
    },
    async viewTicket(ticket) {
      const current = this.selectedTicket && this.selectedTicket.ticket ? this.selectedTicket.ticket : this.selectedTicket;
      if (!current || Number(current.id) !== Number(ticket.id)) {
        this.clearStudentTicketReplyAttachment();
      }
      const body = await this.callApi(() => axios.get("/ticket/" + ticket.id), null, "ticketDetail");
      this.selectedTicket = body.data || ticket;
    },
    async submitStudentTicketReply(payload) {
      const ticket = payload && payload.ticket;
      const content = payload && payload.content ? payload.content.trim() : "";
      if (!ticket || !ticket.id) {
        this.notify("\u8bf7\u5148\u9009\u62e9\u9700\u8981\u56de\u590d\u7684\u5de5\u5355\u3002", "error");
        return;
      }
      if (!content) {
        this.notify("\u8bf7\u586b\u5199\u56de\u590d\u5185\u5bb9\u3002", "error");
        return;
      }
      if (this.studentTicketReplyUploading) {
        this.notify("\u9644\u4ef6\u6b63\u5728\u4e0a\u4f20\uff0c\u8bf7\u7a0d\u540e\u518d\u63d0\u4ea4\u3002", "error");
        return;
      }
      const key = "ticket:" + ticket.id + ":studentReply";
      await this.withSubmit(key, async () => {
        const attachment = this.studentTicketReplyAttachment || {};
        await this.callApi(
          () => axios.post("/ticket/" + ticket.id + "/comment", {
            content,
            userType: 0,
            attachmentName: attachment.attachmentName || "",
            attachmentUrl: attachment.attachmentUrl || "",
            attachmentSize: attachment.attachmentSize || null,
            attachmentType: attachment.attachmentType || ""
          }),
          "\u56de\u590d\u5df2\u63d0\u4ea4\u3002",
          key
        );
        this.clearStudentTicketReplyAttachment();
        await this.viewTicket(ticket);
        await this.loadMyTickets();
      });
    },
    async sendAiQuestion(retryQuestion) {
      const isRetry = typeof retryQuestion === "string" && retryQuestion.trim().length > 0;
      const question = String(isRetry ? retryQuestion : (this.aiQuestion || "")).trim();
      if (!question) {
        this.notify("\u8bf7\u8f93\u5165\u95ee\u9898\u3002", "error");
        return;
      }
      if (question.length > 500) {
        this.notify("\u95ee\u9898\u4e0d\u80fd\u8d85\u8fc7 500 \u4e2a\u5b57\u3002", "error");
        return;
      }
      await this.withSubmit("aiQuestion", async () => {
        this.notify("\u667a\u80fd\u52a9\u624b\u6b63\u5728\u601d\u8003\uff0c\u8bf7\u7a0d\u5019\u3002");
        if (!isRetry) {
          this.chatMessages.push({ role: "user", text: question });
        }
        const pendingMessage = {
          role: "assistant pending",
          text: "\u6b63\u5728\u751f\u6210\u56de\u7b54\u3002"
        };
        this.chatMessages.push(pendingMessage);
        this.scrollChatToEnd();
        try {
          const body = await this.callApi(() => axios.post("/ai/campus/chat", {
            question,
            categoryId: this.selectedCategoryId || null,
            sessionId: this.aiSessionId
          }, { timeout: 60000 }), null, "aiQuestion");
          const data = body.data || {};
          this.aiSessionId = data.sessionId || this.aiSessionId;
          const response = data.response || {};
          this.chatMessages.splice(this.chatMessages.indexOf(pendingMessage), 1, this.aiResponseToMessage(response));
          await this.loadAiSessions();
          this.notify("\u667a\u80fd\u52a9\u624b\u5df2\u56de\u590d\u3002");
          if (!isRetry) {
            this.aiQuestion = "";
          }
        } catch (error) {
          const failedIndex = this.chatMessages.indexOf(pendingMessage);
          const errorMessage = {
            role: "assistant error",
            text: "\u6682\u65f6\u65e0\u6cd5\u83b7\u53d6\u667a\u80fd\u52a9\u624b\u56de\u7b54\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002",
            retryQuestion: question
          };
          if (failedIndex >= 0) {
            this.chatMessages.splice(failedIndex, 1, errorMessage);
          } else {
            this.chatMessages.push(errorMessage);
          }
        } finally {
          this.scrollChatToEnd();
        }
      });
    },
    aiResponseToMessage(response) {
      return {
        role: "assistant",
        text: response.answer || "\u6682\u672a\u8fd4\u56de\u56de\u7b54\u3002",
        intent: response.intent || "",
        confidence: response.confidence,
        sources: Array.isArray(response.sources) ? response.sources : [],
        businessCards: Array.isArray(response.businessCards) ? response.businessCards : [],
        actionDrafts: Array.isArray(response.actionDrafts) ? response.actionDrafts : [],
        fallbackReason: response.fallbackReason || null
      };
    },
    aiStoredMessageToChat(message) {
      const role = message.role === "assistant" ? "assistant" : "user";
      const chatMessage = {
        role,
        text: message.content || ""
      };
      if (role !== "assistant" || !message.metadata) {
        return chatMessage;
      }
      try {
        const metadata = JSON.parse(message.metadata);
        return Object.assign(chatMessage, this.aiResponseToMessage(metadata), {
          text: metadata.answer || message.content || ""
        });
      } catch (error) {
        return chatMessage;
      }
    },
    async openAiBusinessDetail(card) {
      if (!card) {
        return;
      }
      this.aiDetailDialog = {
        open: true,
        title: this.aiDetailTitle(card),
        type: card.type || "business",
        item: card,
        loading: true,
        error: ""
      };
      try {
        const detail = await this.loadAiBusinessDetail(card);
        this.aiDetailDialog.item = detail || card;
      } catch (error) {
        this.aiDetailDialog.error = "\u8be6\u60c5\u6682\u65f6\u65e0\u6cd5\u52a0\u8f7d\uff0c\u5df2\u663e\u793a\u5361\u7247\u4e2d\u7684\u6458\u8981\u4fe1\u606f\u3002";
      } finally {
        this.aiDetailDialog.loading = false;
      }
    },
    async loadAiBusinessDetail(card) {
      const id = card.id || card.messageId || card.orderId;
      if (!id) {
        return card;
      }
      if (card.type === "ticket") {
        const body = await this.callApi(() => axios.get("/ticket/" + id), null, "aiDetail");
        return body.data || card;
      }
      if (card.type === "appointment") {
        const body = await this.callApi(() => axios.get("/appointment-order/" + id), null, "aiDetail");
        return body.data || card;
      }
      if (card.type === "service_point") {
        const body = await this.callApi(() => axios.get("/service-point/" + id), null, "aiDetail");
        return body.data || card;
      }
      if (card.type === "inbox" && card.monthKey && (card.messageId || card.id)) {
        const body = await this.callApi(() => axios.get("/inbox/messages/" + card.monthKey + "/" + (card.messageId || card.id)), null, "aiDetail");
        return body.data || card;
      }
      return card;
    },
    async handleAiActionDraft(draft) {
      if (!draft || !draft.type) {
        return;
      }
      if (draft.type === "create_ticket_draft") {
        await this.openTicketDraft(draft);
        return;
      }
      if (draft.type === "appointment_query_draft") {
        await this.openAppointmentDraft(draft);
        return;
      }
      if (draft.type === "reply_ticket_draft") {
        await this.openTicketReplyDraft(draft);
      }
    },
    async openTicketDraft(draft) {
      const payload = this.aiDraftPayload(draft);
      this.setView("ticket");
      await this.loadTicketServicePoints();
      const point = this.findDraftServicePoint(payload);
      if (point) {
        this.handleTicketPointChange(point.id);
      }
      if (payload.categoryId) {
        this.$set(this.ticketForm, "categoryId", payload.categoryId);
      }
      if (payload.title) {
        this.$set(this.ticketForm, "title", String(payload.title).slice(0, 80));
      }
      if (payload.content) {
        this.$set(this.ticketForm, "content", String(payload.content).slice(0, 1000));
      }
      this.ticketFormErrors = {};
      this.notify("\u5de5\u5355\u8349\u7a3f\u5df2\u586b\u5165\uff0c\u786e\u8ba4\u540e\u518d\u63d0\u4ea4\u3002");
    },
    async openAppointmentDraft(draft) {
      const payload = this.aiDraftPayload(draft);
      this.setView("appointment");
      await this.loadTicketServicePoints();
      const point = this.findDraftServicePoint(payload);
      if (point) {
        await this.selectPoint(point);
      } else {
        await this.loadMyOrders();
      }
      this.notify("\u5df2\u6253\u5f00\u9884\u7ea6\u529e\u7406\uff0c\u8bf7\u624b\u52a8\u9009\u62e9\u65f6\u6bb5\u5e76\u786e\u8ba4\u63d0\u4ea4\u3002");
    },
    async openTicketReplyDraft(draft) {
      const payload = this.aiDraftPayload(draft);
      if (!payload.ticketId) {
        this.notify("\u8349\u7a3f\u7f3a\u5c11\u5173\u8054\u5de5\u5355\uff0c\u8bf7\u5148\u67e5\u770b\u5de5\u5355\u5217\u8868\u3002", "error");
        return;
      }
      this.setView("ticket");
      await this.viewTicket({ id: payload.ticketId });
      this.studentTicketReplyDraft = {
        ticketId: payload.ticketId,
        replyContent: payload.replyContent || "",
        version: Date.now()
      };
      this.notify("\u56de\u590d\u8349\u7a3f\u5df2\u586b\u5165\uff0c\u786e\u8ba4\u540e\u518d\u63d0\u4ea4\u3002");
    },
    aiDraftPayload(draft) {
      return draft && draft.payload && typeof draft.payload === "object" ? draft.payload : {};
    },
    findDraftServicePoint(payload) {
      const points = this.ticketServicePoints || [];
      if (payload.servicePointId) {
        const matched = points.find(point => String(point.id) === String(payload.servicePointId));
        if (matched) {
          return matched;
        }
      }
      if (payload.servicePointName) {
        return points.find(point => String(point.name || "").indexOf(String(payload.servicePointName)) >= 0);
      }
      return null;
    },
    closeAiDetailDialog() {
      this.aiDetailDialog = {
        open: false,
        title: "",
        type: "",
        item: null,
        loading: false,
        error: ""
      };
    },
    aiDetailTitle(item) {
      const record = this.aiDetailRecord(item);
      return record.title || record.name || record.servicePointName || record.slotTitle || record.module || record.failureType || "\u4e1a\u52a1\u8be6\u60c5";
    },
    aiDetailTypeText(type) {
      return ({
        ticket: "\u5de5\u5355",
        appointment: "\u9884\u7ea6",
        service_point: "\u670d\u52a1\u70b9",
        inbox: "\u901a\u77e5",
        admin_log: "\u540e\u53f0\u65e5\u5fd7",
        business: "\u4e1a\u52a1"
      })[type] || type || "\u4e1a\u52a1";
    },
    aiDetailRows(item) {
      const record = this.aiDetailRecord(item);
      if (!record) {
        return [];
      }
      const rows = [
        ["ID", record.id || record.messageId || record.orderId],
        ["\u6807\u9898", record.title || record.name || record.slotTitle],
        ["\u72b6\u6001", record.statusText || record.status || record.readStatus],
        ["\u670d\u52a1\u70b9", record.servicePointName || record.servicePointId],
        ["\u5730\u5740", record.servicePointAddress || record.address || record.detailAddress],
        ["\u5f00\u653e\u65f6\u95f4", record.openHours],
        ["\u5f00\u59cb\u65f6\u95f4", record.startTime],
        ["\u7ed3\u675f\u65f6\u95f4", record.endTime],
        ["\u53d6\u6d88\u65f6\u95f4", record.cancelTime],
        ["\u5b8c\u6210\u65f6\u95f4", record.finishTime],
        ["\u521b\u5efa\u65f6\u95f4", record.createTime],
        ["\u9644\u4ef6", record.attachmentName],
        ["\u64cd\u4f5c", record.operation],
        ["\u6a21\u5757", record.module]
      ];
      return rows
        .filter(row => row[1] !== undefined && row[1] !== null && row[1] !== "")
        .map(row => ({ label: row[0], value: row[1] }));
    },
    aiDetailContent(item) {
      const record = this.aiDetailRecord(item);
      if (!record) {
        return "";
      }
      return record.content || record.description || record.summary || record.remark || record.reason || "";
    },
    aiDetailComments(item) {
      return item && Array.isArray(item.comments) ? item.comments : [];
    },
    aiDetailRecord(item) {
      return item && item.ticket ? item.ticket : item;
    },
    openAiKnowledgeAdmin() {
      this.activeView = "admin";
      this.adminModule = "knowledge";
      this.closeAiDetailDialog();
      this.loadAdminKnowledge();
    },
    async loadAiSessions() {
      const body = await this.callApi(() => axios.get("/ai/session/list"), null, "aiSessions");
      this.aiSessions = campusApi.unwrapRecords(body);
    },
    openAiSessionMenu(event, session) {
      if (!event || !session) {
        return;
      }
      const menuWidth = 152;
      const menuHeight = 92;
      this.aiSessionMenu = {
        open: true,
        x: Math.min(event.clientX, window.innerWidth - menuWidth),
        y: Math.min(event.clientY, window.innerHeight - menuHeight),
        session
      };
    },
    closeAiSessionMenu() {
      if (!this.aiSessionMenu.open) {
        return;
      }
      this.aiSessionMenu = {
        open: false,
        x: 0,
        y: 0,
        session: null
      };
    },
    async toggleAiSessionPinned(session) {
      if (!session || !session.id) {
        return;
      }
      const pinned = Number(session.pinned) !== 1;
      this.closeAiSessionMenu();
      await this.withSubmit("aiSession:" + session.id + ":pin", async () => {
        await this.callApi(
          () => axios.put("/ai/session/" + session.id + "/pin", null, { params: { pinned } }),
          pinned ? "\u4f1a\u8bdd\u5df2\u7f6e\u9876\u3002" : "\u5df2\u53d6\u6d88\u7f6e\u9876\u3002",
          "aiSessionPin"
        );
        await this.loadAiSessions();
      });
    },
    async deleteAiSession(session) {
      if (!session || !session.id) {
        return;
      }
      this.closeAiSessionMenu();
      if (!window.confirm("\u786e\u8ba4\u5220\u9664\u8be5\u667a\u80fd\u52a9\u624b\u4f1a\u8bdd\u5417\uff1f")) {
        return;
      }
      await this.withSubmit("aiSession:" + session.id + ":delete", async () => {
        await this.callApi(
          () => axios.delete("/ai/session/" + session.id),
          "\u4f1a\u8bdd\u5df2\u5220\u9664\u3002",
          "aiSessionDelete"
        );
        if (String(this.aiSessionId) === String(session.id)) {
          this.aiSessionId = null;
          this.chatMessages = [];
        }
        await this.loadAiSessions();
      });
    },
    async clearAiSessions() {
      this.closeAiSessionMenu();
      if (!window.confirm("\u786e\u8ba4\u6e05\u7a7a\u5168\u90e8\u667a\u80fd\u52a9\u624b\u4f1a\u8bdd\u5417\uff1f")) {
        return;
      }
      await this.withSubmit("aiSessions:clear", async () => {
        await this.callApi(
          () => axios.delete("/ai/campus/sessions"),
          "\u667a\u80fd\u52a9\u624b\u4f1a\u8bdd\u5df2\u6e05\u7a7a\u3002",
          "aiSessionClear"
        );
        this.aiSessionId = null;
        this.chatMessages = [];
        this.aiSessions = [];
      });
    },
    async openAiSession(session) {
      this.closeAiSessionMenu();
      await this.withSubmit("aiSession:" + session.id, async () => {
        const body = await this.callApi(() => axios.get("/ai/session/" + session.id + "/messages"), null, "aiMessages");
        this.aiSessionId = session.id;
        this.chatMessages = campusApi.unwrapRecords(body).map(message => this.aiStoredMessageToChat(message));
        this.scrollChatToEnd();
      });
    },
    newAiSession() {
      this.closeAiSessionMenu();
      this.aiSessionId = null;
      this.chatMessages = [];
      this.aiQuestion = "";
    },
    scrollChatToEnd() {
      this.$nextTick(() => {
        const box = this.$el.querySelector(".chat-box");
        if (box) {
          box.scrollTop = box.scrollHeight;
        }
      });
    },
    async loadInboxMessages(reset) {
      if (reset) {
        this.inboxMessages = [];
        this.inboxPage = { nextCursor: null, hasMore: false };
        this.inboxQuery.cursor = null;
        this.selectedInboxMap = {};
      }
      const params = {
        messageType: this.inboxQuery.messageType,
        readStatus: this.inboxQuery.readStatus === "" ? null : Number(this.inboxQuery.readStatus),
        starStatus: this.inboxQuery.starStatus === "" ? null : Number(this.inboxQuery.starStatus),
        monthKey: this.inboxQuery.monthKey,
        pageSize: Number(this.inboxQuery.pageSize || 10),
        cursor: reset ? null : this.inboxPage.nextCursor
      };
      const body = await this.callApi(() => axios.get("/inbox/messages", { params }), null, reset ? "inboxMessages" : "inboxMore");
      const page = body.data || {};
      const records = Array.isArray(page.records) ? page.records : [];
      this.inboxMessages = reset ? records : this.inboxMessages.concat(records);
      this.inboxPage.nextCursor = page.nextCursor || null;
      this.inboxPage.hasMore = Boolean(page.hasMore);
      if (!this.selectedInboxMessage && this.inboxMessages.length > 0) {
        this.selectedInboxMessage = this.inboxMessages[0];
      }
    },
    async loadInboxUnreadCounts() {
      const body = await this.callApi(() => axios.get("/inbox/unread-counts"), null, "inboxUnreadCounts");
      this.inboxUnreadCounts = body.data || { total: 0, typeCounts: {} };
    },
    async openInboxMessage(message) {
      if (!message || !message.messageId) {
        return;
      }
      const monthKey = this.inboxQuery.monthKey || this.currentMonthKey();
      const body = await this.callApi(() => axios.get("/inbox/messages/" + monthKey + "/" + message.messageId), null, "inboxDetail");
      this.selectedInboxMessage = body.data || message;
      this.selectedInboxAppointmentOrder = null;
      if (this.inboxHasAppointmentLink(this.selectedInboxMessage)) {
        await this.loadInboxAppointmentOrder(this.selectedInboxMessage);
      }
      this.$set(message, "readStatus", 1);
      await this.loadInboxUnreadCounts();
    },
    inboxHasTicketLink(message) {
      return message
        && message.businessType === "SERVICE_TICKET"
        && message.businessId;
    },
    inboxHasAppointmentLink(message) {
      return message
        && message.businessType === "APPOINTMENT"
        && message.businessId;
    },
    async loadInboxAppointmentOrder(message) {
      if (!this.inboxHasAppointmentLink(message)) {
        this.selectedInboxAppointmentOrder = null;
        return null;
      }
      const url = this.canAccessAdminView()
        ? "/admin/appointment-order/" + message.businessId
        : "/appointment-order/" + message.businessId;
      const body = await this.callApi(() => axios.get(url), null, "appointmentDetail");
      this.selectedInboxAppointmentOrder = body.data || null;
      return this.selectedInboxAppointmentOrder;
    },
    async openInboxAppointment(message) {
      if (!this.inboxHasAppointmentLink(message)) {
        return;
      }
      const order = this.selectedInboxAppointmentOrder || await this.loadInboxAppointmentOrder(message);
      if (!order || !order.id) {
        return;
      }
      if (this.canAccessAdminView()) {
        this.activeView = "admin";
        this.adminModule = "orders";
        this.adminOrderFilters.status = "";
        this.adminOrders = [order];
        this.adminOrderPage.total = 1;
        this.focusAppointmentOrder(order.id);
        return;
      }
      this.activeView = "appointment";
      this.appointmentOrderFilter = "";
      await this.loadMyOrders();
      this.upsertMyOrder(order);
      this.focusAppointmentOrder(order.id);
    },
    inboxAppointmentActions(message, order) {
      if (!this.inboxHasAppointmentLink(message)) {
        return [];
      }
      const viewAction = {
        key: "view",
        type: "view",
        label: "\u67e5\u770b\u9884\u7ea6",
        variant: "secondary",
        disabled: this.isLoading("appointmentDetail")
      };
      if (this.canAccessAdminView()) {
        return [viewAction];
      }
      const status = Number(order && order.status);
      if (status === 1 && this.isInboxSlotClosedMessage(message, order)) {
        return [viewAction, {
          key: "slots",
          type: "slots",
          label: "\u67e5\u770b\u5176\u4ed6\u65f6\u6bb5",
          variant: "",
          disabled: !order || this.isLoading("slots")
        }];
      }
      if (status === 1) {
        return [viewAction, {
          key: "cancel",
          type: "cancel",
          label: "\u53d6\u6d88\u9884\u7ea6",
          variant: "danger",
          disabled: !order || this.isSubmitting("cancel:" + order.id)
        }];
      }
      if (status === 4) {
        return [viewAction, {
          key: "rebook",
          type: "slots",
          label: "\u91cd\u65b0\u9884\u7ea6",
          variant: "",
          disabled: !order || this.isLoading("slots")
        }];
      }
      if (status === 5) {
        return [viewAction, {
          key: "reason",
          type: "reason",
          label: "\u67e5\u770b\u539f\u56e0",
          variant: "secondary",
          disabled: !order
        }];
      }
      return [viewAction];
    },
    isInboxSlotClosedMessage(message, order) {
      const text = [message && message.title, message && message.summary, message && message.content].join(" ");
      if (text.includes("\u65f6\u6bb5") && text.includes("\u5173\u95ed")) {
        return true;
      }
      return !text.trim() && order && Number(order.slotStatus) === 0;
    },
    async runInboxAppointmentAction(action) {
      if (!action) {
        return;
      }
      if (action.type === "cancel") {
        await this.cancelInboxAppointment();
        return;
      }
      if (action.type === "slots") {
        await this.openInboxAppointmentSlots();
        return;
      }
      if (action.type === "reason") {
        const remark = this.inboxAppointmentRemark(this.selectedInboxAppointmentOrder);
        this.notify(remark === "\u65e0" ? "\u6682\u65e0\u5904\u7406\u5907\u6ce8\u3002" : "\u723d\u7ea6\u539f\u56e0\uff1a" + remark);
        return;
      }
      await this.openInboxAppointment(this.selectedInboxMessage);
    },
    async openInboxAppointmentSlots() {
      const message = this.selectedInboxMessage;
      const order = this.selectedInboxAppointmentOrder || await this.loadInboxAppointmentOrder(message);
      this.activeView = "appointment";
      this.appointmentOrderFilter = "";
      if (order && order.servicePointId) {
        const servicePoint = this.servicePoints.find(item => Number(item.id) === Number(order.servicePointId));
        this.selectedPoint = servicePoint || {
          id: order.servicePointId,
          name: order.servicePointName || "\u5173\u8054\u670d\u52a1\u70b9",
          address: order.servicePointAddress || "",
          status: 1
        };
        await this.loadSlots(order.servicePointId);
      } else if (this.selectedPoint && this.selectedPoint.id) {
        await this.loadSlots(this.selectedPoint.id);
      } else {
        await this.loadServicePoints();
      }
      await this.loadMyOrders();
      if (order && order.id) {
        this.upsertMyOrder(order);
        this.focusAppointmentOrder(order.id);
      }
    },
    async cancelInboxAppointment() {
      const order = this.selectedInboxAppointmentOrder;
      if (!order || Number(order.status) !== 1) {
        return;
      }
      await this.cancelAppointmentOrder(order);
      if (this.selectedInboxMessage) {
        await this.loadInboxAppointmentOrder(this.selectedInboxMessage);
      }
    },
    inboxAppointmentRemark(order) {
      return order && order.remark ? order.remark : "\u65e0";
    },
    async openInboxTicket(message, replyAfterOpen) {
      if (!this.inboxHasTicketLink(message)) {
        return;
      }
      const ticketRef = { id: message.businessId };
      if (this.canAccessAdminView()) {
        this.activeView = "admin";
        this.adminModule = "tickets";
        await this.viewAdminTicketDetail(ticketRef);
        await this.loadAdminTickets();
        if (replyAfterOpen && this.adminSelectedTicket) {
          if (!this.canReplyAdminTicket(this.adminSelectedTicket)) {
            this.notify("\u8be5\u5de5\u5355\u5df2\u7ec8\u6b62\uff0c\u4e0d\u80fd\u7ee7\u7eed\u56de\u590d\u3002", "error");
            return;
          }
          this.openAdminTicketReplyDialog(this.adminSelectedTicket.ticket || this.adminSelectedTicket);
        }
        return;
      }
      this.activeView = "ticket";
      await this.viewTicket(ticketRef);
    },
    async runInboxAction(action, successText) {
      if (this.selectedInboxIds.length === 0) {
        this.notify("\u8bf7\u5148\u9009\u62e9\u8981\u5904\u7406\u7684\u6d88\u606f\u3002", "error");
        return;
      }
      const payload = {
        monthKey: this.inboxQuery.monthKey || this.currentMonthKey(),
        messageIds: this.selectedInboxIds
      };
      await this.withSubmit("inbox:" + action, async () => {
        const urlMap = {
          read: "/inbox/messages/read",
          star: "/inbox/messages/star",
          unstar: "/inbox/messages/unstar",
          delete: "/inbox/messages/delete"
        };
        const method = action === "delete" ? axios.post : axios.patch;
        await this.callApi(() => method(urlMap[action], payload), successText, "inbox:" + action);
        this.selectedInboxMap = {};
        this.selectedInboxMessage = null;
        await this.loadInboxUnreadCounts();
        await this.loadInboxMessages(true);
      });
    },
    async markAllInboxRead() {
      const monthKey = this.inboxQuery.monthKey || this.currentMonthKey();
      await this.withSubmit("inbox:readAll", async () => {
        await this.callApi(
          () => axios.patch("/inbox/messages/read-all", null, { params: { monthKey } }),
          "\u5168\u90e8\u672a\u8bfb\u6d88\u606f\u5df2\u6807\u8bb0\u4e3a\u5df2\u8bfb\u3002",
          "inbox:readAll"
        );
        this.selectedInboxMap = {};
        this.selectedInboxMessage = null;
        await this.loadInboxUnreadCounts();
        await this.loadInboxMessages(true);
      });
    },
    toggleInboxSelection(message) {
      if (!message || !message.messageId) {
        return;
      }
      this.$set(this.selectedInboxMap, message.messageId, !this.selectedInboxMap[message.messageId]);
    },
    isInboxSelected(message) {
      return Boolean(message && this.selectedInboxMap[message.messageId]);
    },
    clearInboxSelection() {
      this.selectedInboxMap = {};
    },
    inboxTypeText(value) {
      return campusApi.displayText(value);
    },
    inboxTargetText(value) {
      return ({
        ALL: "\u5168\u91cf\u7528\u6237",
        USER: "\u6307\u5b9a\u7528\u6237",
        ROLE: "\u6307\u5b9a\u89d2\u8272"
      })[value] || campusApi.displayText(value);
    },
    inboxReadText(value) {
      return Number(value) === 1 ? "\u5df2\u8bfb" : "\u672a\u8bfb";
    },
    inboxDisplaySummary(message) {
      if (!message) {
        return "\u6682\u65e0\u6458\u8981";
      }
      return this.normalizeInboxText(message, message.summary || message.content || "\u6682\u65e0\u6458\u8981");
    },
    inboxDisplayContent(message) {
      if (!message) {
        return "-";
      }
      return this.normalizeInboxText(message, message.content || message.summary || "-");
    },
    normalizeInboxText(message, text) {
      const value = String(text || "").replace(/\r\n/g, "\n").trim();
      if (!value || !message || message.messageType !== "SITE_REPLY") {
        return value || "-";
      }
      const replyPrefix = "\u56de\u590d\u5185\u5bb9\uff1a";
      const replyIndex = value.lastIndexOf(replyPrefix);
      const replyText = replyIndex >= 0 ? value.slice(replyIndex + replyPrefix.length).trim() : "";
      const senderMatch = value.match(/^(.+?)\s+\u56de\u590d\u4e86\u4f60/);
      const senderName = senderMatch && senderMatch[1] ? senderMatch[1].trim() : "";
      if (replyText && senderName) {
        return senderName + "\uff1a" + replyText;
      }
      if (replyText) {
        return replyText;
      }
      return value
        .replace(/\s*\u5728\u300c[^」]+\u300d\u7684/, "\u7684")
        .replace(/\n{2,}/g, "\n")
        .trim();
    },
    inboxStarText(value) {
      return Number(value) === 1 ? "\u5df2\u6807\u661f" : "\u672a\u6807\u661f";
    },
    currentMonthKey() {
      const now = new Date();
      const month = String(now.getMonth() + 1).padStart(2, "0");
      return String(now.getFullYear()) + month;
    },
    connectInboxWebSocket() {
      const token = campusApi.getToken();
      if (!token || !window.WebSocket || !window.CampusWebSocket) {
        this.inboxSocketState = "\u4e0d\u53ef\u7528";
        return;
      }
      if (!this.inboxSocketManager) {
        const stateText = {
          CONNECTING: "\u8fde\u63a5\u4e2d",
          OPEN: "\u5df2\u8fde\u63a5",
          RECONNECT_WAIT: "\u7b49\u5f85\u91cd\u8fde",
          OFFLINE: "\u7f51\u7edc\u79bb\u7ebf",
          CLOSED: "\u5df2\u5173\u95ed",
          AUTH_FAILED: "\u8ba4\u8bc1\u5931\u8d25"
        };
        this.inboxSocketManager = new CampusWebSocket.CampusWebSocketManager({
          urlFactory: () => {
            const protocol = location.protocol === "https:" ? "wss:" : "ws:";
            return protocol + "//" + location.host + "/ws/inbox?token=" + encodeURIComponent(campusApi.getToken());
          },
          onStateChange: state => {
            this.inboxSocketState = stateText[state] || state;
          },
          onMessage: raw => this.handleInboxRealtimeMessage(raw),
          onAuthFailed: () => {
            campusApi.clearToken();
            this.redirectToLogin();
          }
        });
      }
      this.inboxSocketManager.start();
    },
    closeInboxWebSocket(reason) {
      if (this.inboxSocketManager) {
        this.inboxSocketManager.close(reason || "manual close");
      }
    },
    destroyInboxWebSocket() {
      if (this.inboxSocketManager) {
        this.inboxSocketManager.destroy();
        this.inboxSocketManager = null;
      }
    },
    async handleInboxRealtimeMessage(raw) {
      let payload = null;
      try {
        payload = JSON.parse(raw);
      } catch (error) {
        return;
      }
      if (!payload || !payload.messageId) {
        return;
      }
      this.notify("\u6536\u5230\u65b0\u6d88\u606f\uff1a" + (payload.title || payload.messageId));
      await this.loadInboxUnreadCounts();
      if (this.activeView === "inbox") {
        await this.loadInboxMessages(true);
      }
    },
    parseInboxIds(text) {
      return String(text || "")
        .split(/[,，\s]+/)
        .map(item => item.trim())
        .filter(Boolean)
        .map(item => Number(item))
        .filter(item => Number.isFinite(item) && item > 0);
    },
    parseInboxRoles(text) {
      return String(text || "")
        .split(/[,，\s]+/)
        .map(item => item.trim())
        .filter(Boolean);
    },
    buildInboxSendPayload() {
      const form = this.inboxSendForm;
      const payload = {
        messageType: form.messageType,
        targetType: form.targetType,
        title: form.title,
        content: form.content,
        summary: form.summary || null,
        businessType: form.businessType || null,
        businessId: form.businessId ? Number(form.businessId) : null,
        expireTime: form.expireTime || null
      };
      if (form.targetType === "USER") {
        payload.userIds = this.parseInboxIds(form.userIdsText);
      }
      if (form.targetType === "ROLE") {
        payload.roles = this.parseInboxRoles(form.rolesText);
      }
      return payload;
    },
    async sendInboxMessage() {
      const payload = this.buildInboxSendPayload();
      if (!payload.title || !payload.content) {
        this.notify("\u8bf7\u586b\u5199\u6d88\u606f\u6807\u9898\u548c\u5185\u5bb9\u3002", "error");
        return;
      }
      if (payload.targetType === "USER" && (!payload.userIds || payload.userIds.length === 0)) {
        this.notify("\u8bf7\u586b\u5199\u6307\u5b9a\u7528\u6237 ID\u3002", "error");
        return;
      }
      if (payload.targetType === "ROLE" && (!payload.roles || payload.roles.length === 0)) {
        this.notify("\u8bf7\u586b\u5199\u6307\u5b9a\u89d2\u8272\u3002", "error");
        return;
      }
      await this.withSubmit("inbox:send", async () => {
        const body = await this.callApi(() => axios.post("/admin/inbox/messages", payload), "\u6d88\u606f\u5df2\u63d0\u4ea4\uff0c\u5c06\u901a\u8fc7 MQ \u5f02\u6b65\u6295\u9012\u3002", "inbox:send");
        this.lastInboxSent = {
          messageId: body.data,
          monthKey: this.currentMonthKey(),
          title: payload.title
        };
        this.inboxSendForm.title = "";
        this.inboxSendForm.content = "";
        this.inboxSendForm.summary = "";
      });
    },
    async revokeLastInboxMessage() {
      if (!this.lastInboxSent || !this.lastInboxSent.messageId) {
        this.notify("\u6682\u65e0\u53ef\u64a4\u56de\u7684\u6700\u8fd1\u53d1\u9001\u6d88\u606f\u3002", "error");
        return;
      }
      await this.withSubmit("inbox:revoke", async () => {
        await this.callApi(
          () => axios.patch("/admin/inbox/messages/" + this.lastInboxSent.monthKey + "/" + this.lastInboxSent.messageId + "/revoke"),
          "\u6d88\u606f\u5df2\u64a4\u56de\u3002",
          "inbox:revoke"
        );
      });
    },
    async openInboxRevokeDialog() {
      this.inboxRevokeDialogOpen = true;
      this.inboxRevokeMonthKey = this.inboxRevokeMonthKey || this.currentMonthKey();
      this.selectedRevokeInboxMap = {};
      await this.loadRevocableInboxMessages();
    },
    closeInboxRevokeDialog() {
      if (this.isSubmitting("inbox:batchRevoke")) {
        return;
      }
      this.inboxRevokeDialogOpen = false;
      this.selectedRevokeInboxMap = {};
    },
    async loadRevocableInboxMessages() {
      const monthKey = (this.inboxRevokeMonthKey || "").trim();
      if (!/^\d{6}$/.test(monthKey)) {
        this.notify("\u8bf7\u586b\u5199\u6b63\u786e\u7684\u6d88\u606f\u6708\u4efd\u3002", "error");
        return;
      }
      const body = await this.callApi(
        () => axios.get("/admin/inbox/messages/active", { params: { monthKey, pageSize: 50 } }),
        null,
        "inboxRevocableMessages"
      );
      this.inboxRevocableMessages = campusApi.unwrapRecords(body);
      const availableIds = this.inboxRevocableMessages.map(item => Number(item.messageId));
      Object.keys(this.selectedRevokeInboxMap).forEach(key => {
        if (!availableIds.includes(Number(key))) {
          this.$delete(this.selectedRevokeInboxMap, key);
        }
      });
    },
    toggleRevocableInboxMessage(item) {
      if (!item || !item.messageId) {
        return;
      }
      this.$set(this.selectedRevokeInboxMap, item.messageId, !this.selectedRevokeInboxMap[item.messageId]);
    },
    toggleAllRevocableInboxMessages() {
      if (this.inboxRevocableMessages.length === 0) {
        return;
      }
      const selectedAll = this.selectedRevokeInboxIds.length === this.inboxRevocableMessages.length;
      this.selectedRevokeInboxMap = {};
      if (selectedAll) {
        return;
      }
      this.inboxRevocableMessages.forEach(item => {
        this.$set(this.selectedRevokeInboxMap, item.messageId, true);
      });
    },
    async revokeSelectedInboxMessages() {
      const monthKey = (this.inboxRevokeMonthKey || "").trim();
      const messageIds = this.selectedRevokeInboxIds;
      if (!/^\d{6}$/.test(monthKey)) {
        this.notify("\u8bf7\u586b\u5199\u6b63\u786e\u7684\u6d88\u606f\u6708\u4efd\u3002", "error");
        return;
      }
      if (messageIds.length === 0) {
        this.notify("\u8bf7\u5148\u9009\u62e9\u9700\u8981\u64a4\u56de\u7684\u6d88\u606f\u3002", "error");
        return;
      }
      await this.withSubmit("inbox:batchRevoke", async () => {
        await this.callApi(
          () => axios.patch("/admin/inbox/messages/revoke", { monthKey, messageIds }),
          "\u5df2\u64a4\u56de\u9009\u4e2d\u6d88\u606f\u3002",
          "inbox:batchRevoke"
        );
        this.selectedRevokeInboxMap = {};
        await this.loadRevocableInboxMessages();
        await this.loadInboxUnreadCounts();
        if (this.activeView === "inbox") {
          await this.loadInboxMessages(true);
        }
      });
    },
    async selectAdminModule(module) {
      if (!module || this.adminModule === module.key) {
        return;
      }
      this.adminModule = module.key;
      await this.loadAdminModule(module.key);
    },
    async loadAdminModule(moduleKey) {
      const key = moduleKey || this.adminModule;
      if (key === "tickets") {
        await this.loadAdminTickets();
        return;
      }
      if (key === "inbox") {
        return;
      }
      if (key === "orders") {
        await this.loadAdminOrders();
        return;
      }
      if (key === "failures") {
        await this.loadAppointmentFailureLogs();
        return;
      }
      if (key === "points") {
        await this.loadAdminServicePoints();
        return;
      }
      if (key === "slots") {
        await this.loadAdminSlots();
        return;
      }
      if (key === "knowledge") {
        await this.loadAdminKnowledge();
        return;
      }
      if (key === "aiTrace") {
        await this.loadAdminAiTraceDashboard();
        return;
      }
      if (key === "logs") {
        await this.loadOperationLogs();
      }
    },
    adminModuleMeta(key) {
      if (key === "tickets") {
        return this.adminTicketPage.total + " \u5f20";
      }
      if (key === "orders") {
        return this.adminOrderPage.total + " \u6761";
      }
      if (key === "failures") {
        return this.appointmentFailureLogPage.total + " \u6761";
      }
      if (key === "points") {
        return this.adminServicePointPage.total + " \u4e2a";
      }
      if (key === "slots") {
        return this.adminSlots.length + " \u4e2a";
      }
      if (key === "knowledge") {
        return this.adminKnowledge.length + " \u6761";
      }
      if (key === "aiTrace") {
        return this.adminAiTracePage.total + " \u6761";
      }
      if (key === "logs") {
        return this.operationLogs.length + " \u6761";
      }
      return this.lastInboxSent ? "\u6700\u8fd1 " + this.lastInboxSent.messageId : "\u6d88\u606f";
    },
    async loadAdminTickets() {
      const body = await this.callApi(
        () => axios.get("/admin/ticket/page", { params: this.buildAdminTicketParams() }),
        null,
        "adminTickets"
      );
      this.adminTickets = campusApi.unwrapRecords(body);
      this.adminTicketPage.total = Number(body.total || this.adminTickets.length || 0);
    },
    async viewAdminTicketDetail(ticket) {
      if (!ticket || !ticket.id) {
        return;
      }
      const body = await this.callApi(() => axios.get("/ticket/" + ticket.id), null, "adminTicketDetail");
      this.adminSelectedTicket = body.data || ticket;
    },
    canReplyAdminTicket(ticket) {
      const record = ticket && ticket.ticket ? ticket.ticket : ticket;
      const status = Number(record && record.status);
      return Boolean(record && record.id) && ![3, 4, 5].includes(status);
    },
    closeAdminTicketDetail() {
      this.adminSelectedTicket = null;
    },
    buildAdminTicketParams() {
      const filters = this.adminTicketFilters;
      const params = { current: this.adminTicketPage.current };
      if (filters.status !== "") {
        params.status = Number(filters.status);
      }
      if (this.isAdminUser() && filters.servicePointId) {
        params.servicePointId = Number(filters.servicePointId);
      }
      if ((filters.requester || "").trim()) {
        params.requester = filters.requester.trim();
      }
      if (filters.startTime) {
        params.startTime = filters.startTime;
      }
      if (filters.endTime) {
        params.endTime = filters.endTime;
      }
      if (filters.sortOrder) {
        params.sortOrder = filters.sortOrder;
      }
      if (filters.studentReplyRequired !== "") {
        params.studentReplyRequired = Number(filters.studentReplyRequired);
      }
      return params;
    },
    async applyAdminTicketFilters() {
      if (this.adminTicketFilters.startTime && this.adminTicketFilters.endTime
          && this.adminTicketFilters.startTime > this.adminTicketFilters.endTime) {
        this.notify("\u5f00\u59cb\u65f6\u95f4\u4e0d\u80fd\u665a\u4e8e\u7ed3\u675f\u65f6\u95f4\u3002", "error");
        return;
      }
      this.adminTicketPage.current = 1;
      await this.loadAdminTickets();
    },
    async resetAdminTicketFilters() {
      this.adminTicketFilters = {
        status: "",
        servicePointId: "",
        requester: "",
        startTime: "",
        endTime: "",
        sortOrder: "desc",
        studentReplyRequired: ""
      };
      this.adminTicketPage.current = 1;
      await this.loadAdminTickets();
    },
    acceptTicket(ticket) {
      this.openAdminTicketReplyDialog(ticket);
    },
    openAdminTicketReplyDialog(ticket) {
      if (!ticket || !ticket.id) {
        return;
      }
      this.adminSelectedTicket = null;
      this.adminTicketReplyDialog = {
        open: true,
        ticket,
        remark: "",
        attachmentName: "",
        attachmentUrl: "",
        attachmentSize: null,
        attachmentType: "",
        needStudentReply: false
      };
      this.adminTicketReplyErrors = {};
      this.adminTicketReplyUploading = false;
    },
    closeAdminTicketReplyDialog() {
      const ticket = this.adminTicketReplyDialog.ticket;
      if (ticket && (this.isSubmitting("ticket:" + ticket.id + ":accept") || this.isSubmitting("ticket:" + ticket.id + ":reply"))) {
        return;
      }
      if (this.adminTicketReplyUploading) {
        return;
      }
      this.adminTicketReplyDialog = {
        open: false,
        ticket: null,
        remark: "",
        attachmentName: "",
        attachmentUrl: "",
        attachmentSize: null,
        attachmentType: "",
        needStudentReply: false
      };
      this.adminTicketReplyErrors = {};
    },
    async submitTicketProcessing() {
      const ticket = this.adminTicketReplyDialog.ticket;
      if (!ticket || !ticket.id) {
        return;
      }
      await this.updateAdminTicket(ticket, "accept", () => axios.put("/admin/ticket/" + ticket.id + "/accept"), "\u5de5\u5355\u5df2\u53d7\u7406\u3002");
      this.closeAdminTicketReplyDialog();
    },
    async submitTicketReply() {
      const dialog = this.adminTicketReplyDialog;
      const ticket = dialog.ticket;
      const remark = (dialog.remark || "").trim();
      if (!ticket || !ticket.id) {
        return;
      }
      if (!remark) {
        this.adminTicketReplyErrors = Object.assign({}, this.adminTicketReplyErrors, { remark: "\u8bf7\u586b\u5199\u5907\u6ce8\u3002" });
        return;
      }
      const errors = Object.assign({}, this.adminTicketReplyErrors);
      delete errors.remark;
      this.adminTicketReplyErrors = errors;
      const payload = {
        remark,
        attachmentName: dialog.attachmentName,
        attachmentUrl: dialog.attachmentUrl,
        attachmentSize: dialog.attachmentSize,
        attachmentType: dialog.attachmentType,
        needStudentReply: Boolean(dialog.needStudentReply)
      };
      await this.updateAdminTicket(
        ticket,
        "reply",
        () => axios.post("/admin/ticket/" + ticket.id + "/reply", payload),
        "\u5de5\u5355\u56de\u590d\u5df2\u53d1\u9001\u3002"
      );
      this.closeAdminTicketReplyDialog();
    },
    async handleAdminTicketReplyFileChange(event) {
      const input = event && event.target;
      const file = input && input.files && input.files[0];
      if (!file) {
        return;
      }
      const errors = Object.assign({}, this.adminTicketReplyErrors);
      const allowed = /\.(jpg|jpeg|png|gif|webp|bmp|pdf|doc|docx|xls|xlsx|ppt|pptx|txt|zip|rar|7z)$/i;
      if (!allowed.test(file.name || "")) {
        errors.attachment = "\u4ec5\u652f\u6301\u56fe\u7247\u3001\u6587\u6863\u548c\u538b\u7f29\u5305\u9644\u4ef6\u3002";
        this.adminTicketReplyErrors = errors;
        input.value = "";
        return;
      }
      if (file.size > 20 * 1024 * 1024) {
        errors.attachment = "\u9644\u4ef6\u4e0d\u80fd\u8d85\u8fc7 20MB\u3002";
        this.adminTicketReplyErrors = errors;
        input.value = "";
        return;
      }
      delete errors.attachment;
      this.adminTicketReplyErrors = errors;
      const formData = new FormData();
      formData.append("file", file);
      this.adminTicketReplyUploading = true;
      try {
        const body = await this.callApi(() => axios.post("/ticket/attachment", formData, {
          headers: { "Content-Type": "multipart/form-data" }
        }), "\u9644\u4ef6\u5df2\u4e0a\u4f20\u3002", "ticketReplyAttachment");
        const attachment = body.data || {};
        this.$set(this.adminTicketReplyDialog, "attachmentName", attachment.name || file.name);
        this.$set(this.adminTicketReplyDialog, "attachmentUrl", attachment.url || "");
        this.$set(this.adminTicketReplyDialog, "attachmentSize", attachment.size || file.size);
        this.$set(this.adminTicketReplyDialog, "attachmentType", attachment.type || file.type || "");
      } catch (error) {
        errors.attachment = String(error || "\u9644\u4ef6\u4e0a\u4f20\u5931\u8d25\u3002");
        this.adminTicketReplyErrors = errors;
      } finally {
        this.adminTicketReplyUploading = false;
        input.value = "";
      }
    },
    clearAdminTicketReplyAttachment() {
      this.$set(this.adminTicketReplyDialog, "attachmentName", "");
      this.$set(this.adminTicketReplyDialog, "attachmentUrl", "");
      this.$set(this.adminTicketReplyDialog, "attachmentSize", null);
      this.$set(this.adminTicketReplyDialog, "attachmentType", "");
      const errors = Object.assign({}, this.adminTicketReplyErrors);
      delete errors.attachment;
      this.adminTicketReplyErrors = errors;
    },
    async finishTicket(ticket) {
      await this.updateAdminTicket(ticket, "finish", () => axios.put("/admin/ticket/" + ticket.id + "/finish"), "\u5de5\u5355\u5df2\u5b8c\u6210\u3002");
    },
    async closeTicket(ticket) {
      await this.updateAdminTicket(ticket, "close", () => axios.put("/admin/ticket/" + ticket.id + "/close"), "\u5de5\u5355\u5df2\u5173\u95ed\u3002");
    },
    async rejectTicket(ticket) {
      await this.updateAdminTicket(ticket, "reject", () => axios.put("/admin/ticket/" + ticket.id + "/reject"), "\u5de5\u5355\u5df2\u62d2\u7edd\u3002");
    },
    openDeleteTicket(ticket) {
      this.deleteTicketTarget = ticket;
      this.deleteTicketRemark = "";
    },
    closeDeleteTicket() {
      if (this.deleteTicketTarget && this.isSubmitting("ticket:" + this.deleteTicketTarget.id + ":delete")) {
        return;
      }
      this.deleteTicketTarget = null;
      this.deleteTicketRemark = "";
    },
    async submitDeleteTicket() {
      const ticket = this.deleteTicketTarget;
      const remark = (this.deleteTicketRemark || "").trim();
      if (!ticket || !ticket.id) {
        return;
      }
      if (!remark) {
        this.notify("\u8bf7\u586b\u5199\u5220\u9664\u5907\u6ce8\u3002", "error");
        return;
      }
      await this.updateAdminTicket(
        ticket,
        "delete",
        () => axios.delete("/admin/ticket/" + ticket.id, { data: { remark } }),
        "\u5de5\u5355\u5df2\u5220\u9664\uff0c\u5b66\u751f\u7aef\u5c06\u663e\u793a\u5220\u9664\u5907\u6ce8\u3002"
      );
      this.closeDeleteTicket();
    },
    async updateAdminTicket(ticket, actionName, request, successText) {
      const key = "ticket:" + ticket.id + ":" + actionName;
      await this.withSubmit(key, async () => {
        this.$set(this.ticketActionMap, ticket.id, actionName);
        try {
          await this.callApi(request, successText, key);
          await this.loadAdminTickets();
          if (this.adminSelectedTicket) {
            const selected = this.adminSelectedTicket.ticket || this.adminSelectedTicket;
            if (selected && Number(selected.id) === Number(ticket.id)) {
              await this.viewAdminTicketDetail(ticket);
            }
          }
          await this.loadOperationLogs();
        } finally {
          this.$delete(this.ticketActionMap, ticket.id);
        }
      });
    },
    async loadAdminOrders() {
      const params = this.buildAdminOrderParams(true);
      const body = await this.callApi(
        () => axios.get("/admin/appointment-order/page", { params }),
        null,
        "adminOrders"
      );
      this.adminOrders = campusApi.unwrapRecords(body);
      this.adminOrderPage.total = Number(body.total || this.adminOrders.length || 0);
      await this.loadAdminOrderStats();
    },
    buildAdminOrderParams(includePage) {
      const filters = this.adminOrderFilters;
      const params = includePage ? { current: this.adminOrderPage.current } : {};
      if (filters.servicePointId) {
        params.servicePointId = Number(filters.servicePointId);
      }
      if (filters.status !== "" && includePage) {
        params.status = Number(filters.status);
      }
      if (filters.userId) {
        params.userId = Number(filters.userId);
      }
      if (filters.startTime) {
        params.startTime = filters.startTime;
      }
      if (filters.endTime) {
        params.endTime = filters.endTime;
      }
      return params;
    },
    async loadAdminOrderStats() {
      const body = await this.callApi(
        () => axios.get("/admin/appointment-order/stats", { params: this.buildAdminOrderParams(false) }),
        null,
        "adminOrderStats"
      );
      this.adminOrderStats = body.data || { pending: 0, today: 0, finished: 0, abnormal: 0 };
    },
    async applyAdminOrderFilters() {
      if (this.adminOrderFilters.startTime && this.adminOrderFilters.endTime
          && this.adminOrderFilters.startTime > this.adminOrderFilters.endTime) {
        this.notify("\u5f00\u59cb\u65f6\u95f4\u4e0d\u80fd\u665a\u4e8e\u7ed3\u675f\u65f6\u95f4\u3002", "error");
        return;
      }
      this.adminOrderPage.current = 1;
      await this.loadAdminOrders();
    },
    async finishAppointmentOrder(order) {
      this.openAdminAppointmentActionDialog(order, "finish");
    },
    async markAppointmentNoShow(order) {
      this.openAdminAppointmentActionDialog(order, "no-show");
    },
    openAdminAppointmentActionDialog(order, action) {
      if (!order || !order.id || Number(order.status) !== 1) {
        return;
      }
      this.adminAppointmentActionDialog = {
        open: true,
        action,
        order,
        remark: order.remark || "",
        internalRemark: order.internalRemark || ""
      };
    },
    closeAdminAppointmentActionDialog() {
      const key = this.adminAppointmentActionSubmitKey();
      if (key && this.isSubmitting(key)) {
        return;
      }
      this.adminAppointmentActionDialog = {
        open: false,
        action: "",
        order: null,
        remark: "",
        internalRemark: ""
      };
    },
    adminAppointmentActionSubmitKey() {
      const dialog = this.adminAppointmentActionDialog;
      return dialog && dialog.order ? "appointmentOrder:" + dialog.order.id + ":" + dialog.action : "";
    },
    adminAppointmentActionTitle() {
      return this.adminAppointmentActionDialog.action === "finish" ? "\u5b8c\u6210\u9884\u7ea6" : "\u6807\u8bb0\u723d\u7ea6";
    },
    adminAppointmentActionButtonText() {
      return this.adminAppointmentActionDialog.action === "finish" ? "\u786e\u8ba4\u5b8c\u6210" : "\u786e\u8ba4\u723d\u7ea6";
    },
    adminAppointmentActionPublicLabel() {
      return this.adminAppointmentActionDialog.action === "finish" ? "\u5b8c\u6210\u5907\u6ce8" : "\u723d\u7ea6\u539f\u56e0";
    },
    async submitAdminAppointmentAction() {
      const dialog = this.adminAppointmentActionDialog;
      const order = dialog.order;
      if (!order || !order.id) {
        return;
      }
      const action = dialog.action;
      const remark = (dialog.remark || "").trim();
      const internalRemark = (dialog.internalRemark || "").trim();
      const params = {};
      if (remark) {
        params.remark = remark;
      }
      if (internalRemark) {
        params.internalRemark = internalRemark;
      }
      const url = "/admin/appointment-order/" + order.id + (action === "finish" ? "/finish" : "/no-show");
      await this.updateAdminAppointmentOrder(
        order,
        action,
        () => axios.put(url, null, { params }),
        action === "finish" ? "\u9884\u7ea6\u5df2\u5b8c\u6210\u3002" : "\u9884\u7ea6\u5df2\u6807\u8bb0\u4e3a\u723d\u7ea6\u3002"
      );
      this.closeAdminAppointmentActionDialog();
    },
    async updateAdminAppointmentOrder(order, actionName, request, successText) {
      const key = "appointmentOrder:" + order.id + ":" + actionName;
      await this.withSubmit(key, async () => {
        await this.callApi(request, successText, key);
        await this.loadAdminOrders();
        await this.loadOperationLogs();
      });
    },
    async deleteAppointmentOrder(order) {
      if (!order || !order.id) {
        return;
      }
      if (!window.confirm("\u5220\u9664\u540e\u4e0d\u4f1a\u5728\u9884\u7ea6\u8ba2\u5355\u5217\u8868\u4e2d\u663e\u793a\u3002\u786e\u8ba4\u5220\u9664\u9884\u7ea6\u8ba2\u5355 " + order.id + " \u5417\uff1f")) {
        return;
      }
      const key = "appointmentOrder:" + order.id + ":delete";
      await this.withSubmit(key, async () => {
        await this.callApi(
          () => axios.delete("/admin/appointment-order/" + order.id),
          "\u9884\u7ea6\u8ba2\u5355\u5df2\u5220\u9664\u3002",
          key
        );
        if (String(this.selectedAppointmentOrderId) === String(order.id)) {
          this.selectedAppointmentOrderId = "";
        }
        await this.loadAdminOrders();
        await this.loadOperationLogs();
      });
    },
    async loadAppointmentFailureLogs() {
      const params = { current: this.appointmentFailureLogPage.current };
      if (this.appointmentFailureFilters.failureType) {
        params.failureType = this.appointmentFailureFilters.failureType;
      }
      if (this.appointmentFailureFilters.status) {
        params.status = this.appointmentFailureFilters.status;
      }
      const body = await this.callApi(
        () => axios.get("/admin/appointment-failure-log/page", { params }),
        null,
        "appointmentFailureLogs"
      );
      this.appointmentFailureLogs = campusApi.unwrapRecords(body);
      this.appointmentFailureLogPage.total = Number(body.total || this.appointmentFailureLogs.length || 0);
    },
    async applyAppointmentFailureFilters() {
      this.appointmentFailureLogPage.current = 1;
      await this.loadAppointmentFailureLogs();
    },
    async loadAdminServicePoints() {
      const body = await this.callApi(() => axios.get("/admin/service-point/page", { params: { current: this.adminServicePointPage.current } }), null, "adminServicePoints");
      this.adminServicePoints = campusApi.unwrapRecords(body);
      this.adminServicePointPage.total = Number(body.total || this.adminServicePoints.length || 0);
    },
    async createServicePoint() {
      if (!this.servicePointForm.name || !this.servicePointForm.categoryId) {
        this.notify("\u8BF7\u586B\u5199\u670D\u52A1\u70B9\u540D\u79F0\u548C\u5206\u7C7B\u7F16\u53F7\u3002", "error");
        return;
      }
      const payload = Object.assign({}, this.servicePointForm, {
        categoryId: Number(this.servicePointForm.categoryId),
        x: Number(this.servicePointForm.x || 117.1201),
        y: Number(this.servicePointForm.y || 36.6812),
        status: Number(this.servicePointForm.status || 1),
        score: Number(this.servicePointForm.score || 45),
        serviceCount: Number(this.servicePointForm.serviceCount || 0)
      });
      await this.withSubmit("createServicePoint", async () => {
        await this.callApi(() => axios.post("/admin/service-point", payload), "\u670D\u52A1\u70B9\u5DF2\u65B0\u589E\u3002", "createServicePoint");
        this.servicePointForm.name = "";
        this.servicePointForm.address = "";
        this.servicePointForm.area = "";
        this.servicePointForm.phone = "";
        this.servicePointForm.description = "";
        await this.loadAdminServicePoints();
      });
    },
    openEditServicePoint(point) {
      this.editingServicePoint = Object.assign({}, point);
      this.editServicePointForm = {
        id: point.id,
        name: point.name || "",
        categoryId: point.categoryId || "",
        managerId: point.managerId || "",
        coverImage: point.coverImage || "",
        area: point.area || "",
        address: point.address || "",
        x: point.x == null ? 117.1201 : point.x,
        y: point.y == null ? 36.6812 : point.y,
        openHours: point.openHours || "",
        phone: point.phone || "",
        description: point.description || "",
        status: point.status == null ? 2 : point.status,
        score: point.score == null ? 45 : point.score,
        serviceCount: point.serviceCount == null ? 0 : point.serviceCount
      };
    },
    closeEditServicePoint() {
      if (this.editServicePointForm && this.isSubmitting("servicePoint:" + this.editServicePointForm.id + ":edit")) {
        return;
      }
      this.editingServicePoint = null;
      this.editServicePointForm = null;
    },
    async submitEditServicePoint() {
      const form = this.editServicePointForm;
      if (!form || !form.id) {
        return;
      }
      if (!form.name || !form.categoryId) {
        this.notify("\u8BF7\u586B\u5199\u670D\u52A1\u70B9\u540D\u79F0\u548C\u5206\u7C7B\u7F16\u53F7\u3002", "error");
        return;
      }
      const payload = Object.assign({}, form, {
        categoryId: Number(form.categoryId),
        managerId: form.managerId ? Number(form.managerId) : null,
        x: Number(form.x || 117.1201),
        y: Number(form.y || 36.6812),
        status: Number(form.status == null ? 2 : form.status),
        score: Number(form.score || 45),
        serviceCount: Number(form.serviceCount || 0)
      });
      await this.updateAdminResource(
        "servicePoint:" + form.id + ":edit",
        () => axios.put("/admin/service-point", payload),
        "\u670D\u52A1\u70B9\u4FE1\u606F\u5DF2\u66F4\u65B0\u3002\u82E5\u7531\u7F51\u70B9\u7BA1\u7406\u5458\u4FEE\u6539\uFF0C\u5C06\u8FDB\u5165\u5F85\u5BA1\u6838\u72B6\u6001\u3002",
        this.loadAdminServicePoints
      );
      this.closeEditServicePoint();
    },
    async approveServicePoint(point) {
      this.reviewServicePoint = Object.assign({}, point);
    },
    closeServicePointReview() {
      if (this.reviewServicePoint && this.isSubmitting("servicePoint:" + this.reviewServicePoint.id + ":approve")) {
        return;
      }
      this.reviewServicePoint = null;
    },
    reviewServicePointRows(point) {
      if (!point) {
        return [];
      }
      return [
        { label: "\u7f16\u53f7", value: point.id },
        { label: "\u540d\u79f0", value: point.name },
        { label: "\u5206\u7c7b\u7f16\u53f7", value: point.categoryId },
        { label: "\u7ba1\u7406\u5458\u7f16\u53f7", value: point.managerId },
        { label: "\u5c01\u9762\u56fe", value: point.coverImage },
        { label: "\u533a\u57df", value: point.area },
        { label: "\u5730\u5740", value: point.address },
        { label: "\u5750\u6807 X", value: point.x },
        { label: "\u5750\u6807 Y", value: point.y },
        { label: "\u5f00\u653e\u65f6\u95f4", value: point.openHours },
        { label: "\u8054\u7cfb\u7535\u8bdd", value: point.phone },
        { label: "\u8bf4\u660e", value: point.description },
        { label: "\u72b6\u6001", value: this.servicePointStatus(point.status) },
        { label: "\u8bc4\u5206", value: point.score },
        { label: "\u670d\u52a1\u6b21\u6570", value: point.serviceCount },
        { label: "\u521b\u5efa\u65f6\u95f4", value: this.formatTime(point.createTime) },
        { label: "\u66f4\u65b0\u65f6\u95f4", value: this.formatTime(point.updateTime) },
        { label: "\u8ddd\u79bb", value: point.distance == null ? "" : point.distance }
      ];
    },
    displayServicePointValue(value) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return value;
    },
    async confirmApproveServicePoint() {
      const point = this.reviewServicePoint;
      if (!point) {
        return;
      }
      await this.updateAdminResource(
        "servicePoint:" + point.id + ":approve",
        () => axios.put("/admin/service-point/" + point.id + "/approve"),
        "\u670D\u52A1\u70B9\u5DF2\u5BA1\u6838\u3002",
        this.loadAdminServicePoints
      );
      this.reviewServicePoint = null;
    },
    async enableServicePoint(point) {
      if (!this.isAdminUser()) {
        this.notify("\u53EA\u6709\u5E73\u53F0\u7BA1\u7406\u5458\u53EF\u4EE5\u542F\u7528\u670D\u52A1\u70B9\u3002", "error");
        return;
      }
      if (Number(point.status) === 2) {
        this.notify("\u5F85\u5BA1\u6838\u670D\u52A1\u70B9\u9700\u8981\u5148\u7531\u5E73\u53F0\u7BA1\u7406\u5458\u5BA1\u6838\u3002", "error");
        return;
      }
      await this.updateAdminResource(
        "servicePoint:" + point.id + ":enable",
        () => axios.put("/admin/service-point/" + point.id + "/enable"),
        "\u670D\u52A1\u70B9\u5DF2\u542F\u7528\u3002",
        this.loadAdminServicePoints
      );
    },
    async disableServicePoint(point) {
      if (!this.isAdminUser()) {
        this.notify("\u53EA\u6709\u5E73\u53F0\u7BA1\u7406\u5458\u53EF\u4EE5\u7981\u7528\u670D\u52A1\u70B9\u3002", "error");
        return;
      }
      await this.updateAdminResource(
        "servicePoint:" + point.id + ":disable",
        () => axios.put("/admin/service-point/" + point.id + "/disable"),
        "\u670D\u52A1\u70B9\u5DF2\u7981\u7528\u3002",
        this.loadAdminServicePoints
      );
    },
    async deleteServicePoint(point) {
      if (!this.isAdminUser()) {
        this.notify("\u53EA\u6709\u5E73\u53F0\u7BA1\u7406\u5458\u53EF\u4EE5\u5220\u9664\u670D\u52A1\u70B9\u3002", "error");
        return;
      }
      if (Number(point.status) !== 0) {
        this.notify("\u8BF7\u5148\u7981\u7528\u670D\u52A1\u70B9\uFF0C\u518D\u6267\u884C\u5220\u9664\u3002", "error");
        return;
      }
      if (!window.confirm("\u5220\u9664\u540E\u4E0D\u53EF\u6062\u590D\u3002\u786E\u8BA4\u5220\u9664\u201C" + (point.name || point.id) + "\u201D\u5417\uFF1F")) {
        return;
      }
      await this.updateAdminResource(
        "servicePoint:" + point.id + ":delete",
        () => axios.delete("/admin/service-point/" + point.id),
        "\u670D\u52A1\u70B9\u5DF2\u5220\u9664\u3002",
        this.loadAdminServicePoints
      );
    },
    async loadAdminSlots() {
      const body = await this.callApi(() => axios.get("/admin/appointment-slot/page", { params: { current: 1 } }), null, "adminSlots");
      this.adminSlots = campusApi.unwrapRecords(body);
    },
    async createAppointmentSlot() {
      if (!this.slotForm.servicePointId || !this.slotForm.title || !this.slotForm.startTime || !this.slotForm.endTime) {
        this.notify("\u8BF7\u586B\u5199\u670D\u52A1\u70B9\u3001\u6807\u9898\u548C\u9884\u7EA6\u65F6\u95F4\u3002", "error");
        return;
      }
      const totalQuota = Number(this.slotForm.totalQuota || 0);
      const availableQuota = Number(this.slotForm.availableQuota || totalQuota);
      if (totalQuota <= 0 || availableQuota < 0 || availableQuota > totalQuota) {
        this.notify("\u8BF7\u68C0\u67E5\u9884\u7EA6\u540D\u989D\u3002", "error");
        return;
      }
      const payload = Object.assign({}, this.slotForm, {
        servicePointId: Number(this.slotForm.servicePointId),
        totalQuota,
        availableQuota,
        status: Number(this.slotForm.status || 1)
      });
      await this.withSubmit("createSlot", async () => {
        await this.callApi(() => axios.post("/admin/appointment-slot", payload), "\u9884\u7EA6\u540D\u989D\u5DF2\u53D1\u5E03\u3002", "createSlot");
        this.slotForm.title = "\u6821\u56ED\u670D\u52A1\u9884\u7EA6";
        this.slotForm.description = "";
        await this.loadAdminSlots();
      });
    },
    async closeSlot(slot) {
      await this.updateAdminResource(
        "slot:" + slot.id + ":close",
        () => axios.put("/admin/appointment-slot/" + slot.id + "/close"),
        "\u9884\u7EA6\u540D\u989D\u5DF2\u5173\u95ED\u3002",
        this.loadAdminSlots
      );
    },
    async openSlot(slot) {
      await this.updateAdminResource(
        "slot:" + slot.id + ":open",
        () => axios.put("/admin/appointment-slot/" + slot.id + "/open"),
        "\u9884\u7EA6\u540D\u989D\u5DF2\u5F00\u542F\u3002",
        this.loadAdminSlots
      );
    },
    async syncSlotQuota(slot) {
      await this.updateAdminResource(
        "slot:" + slot.id + ":sync",
        () => axios.put("/admin/appointment-slot/" + slot.id + "/sync-quota"),
        "\u9884\u7EA6\u540D\u989D\u5DF2\u540C\u6B65\u5230\u7F13\u5B58\u3002",
        this.loadAdminSlots
      );
    },
    async deleteSlot(slot) {
      if (Number(slot.status) !== 0) {
        this.notify("\u8BF7\u5148\u5173\u95ED\u9884\u7EA6\u540D\u989D\uFF0C\u518D\u6267\u884C\u5220\u9664\u3002", "error");
        return;
      }
      if (!window.confirm("\u5220\u9664\u540E\u4E0D\u53EF\u6062\u590D\u3002\u786E\u8BA4\u5220\u9664\u201C" + (slot.title || slot.id) + "\u201D\u5417\uFF1F")) {
        return;
      }
      await this.updateAdminResource(
        "slot:" + slot.id + ":delete",
        () => axios.delete("/admin/appointment-slot/" + slot.id),
        "\u9884\u7EA6\u540D\u989D\u5DF2\u5220\u9664\u3002",
        this.loadAdminSlots
      );
    },
    async loadAdminKnowledge() {
      const body = await this.callApi(() => axios.get("/admin/ai-knowledge/page", { params: { current: 1 } }), null, "adminKnowledge");
      this.adminKnowledge = campusApi.unwrapRecords(body);
    },
    async createAiKnowledge() {
      if (!this.knowledgeForm.title || !this.knowledgeForm.content) {
        this.notify("\u8BF7\u586B\u5199\u77E5\u8BC6\u6807\u9898\u548C\u5185\u5BB9\u3002", "error");
        return;
      }
      const payload = Object.assign({}, this.knowledgeForm, {
        status: Number(this.knowledgeForm.status || 1)
      });
      await this.withSubmit("createKnowledge", async () => {
        await this.callApi(() => axios.post("/admin/ai-knowledge", payload), "\u667A\u80FD\u77E5\u8BC6\u5DF2\u65B0\u589E\u5E76\u89E6\u53D1\u540C\u6B65\u3002", "createKnowledge");
        this.knowledgeForm.title = "";
        this.knowledgeForm.content = "";
        await this.loadAdminKnowledge();
      });
    },
    async enableKnowledge(doc) {
      await this.updateAdminResource(
        "knowledge:" + doc.id + ":enable",
        () => axios.put("/admin/ai-knowledge/" + doc.id + "/enable"),
        "\u667A\u80FD\u77E5\u8BC6\u5DF2\u542F\u7528\u3002",
        this.loadAdminKnowledge
      );
    },
    async disableKnowledge(doc) {
      await this.updateAdminResource(
        "knowledge:" + doc.id + ":disable",
        () => axios.put("/admin/ai-knowledge/" + doc.id + "/disable"),
        "\u667A\u80FD\u77E5\u8BC6\u5DF2\u7981\u7528\u3002",
        this.loadAdminKnowledge
      );
    },
    openKnowledgeDetail(doc) {
      if (!doc || !doc.id) {
        return;
      }
      this.selectedKnowledgeDetail = doc;
    },
    closeKnowledgeDetail() {
      this.selectedKnowledgeDetail = null;
    },
    async syncAiKnowledge() {
      await this.withSubmit("syncKnowledge", async () => {
        await this.callApi(() => axios.put("/admin/ai-knowledge/sync-agent"), "\u667A\u80FD\u77E5\u8BC6\u5E93\u5DF2\u540C\u6B65\u5230\u667A\u80FD\u52A9\u624B\u3002", "syncKnowledge");
        await this.loadAdminKnowledge();
      });
    },
    async loadAdminAiTraceDashboard() {
      await Promise.all([
        this.loadAdminAiTraceMetrics(),
        this.loadAdminAiTraces()
      ]);
    },
    async loadAdminAiTraceMetrics() {
      const body = await this.callApi(() => axios.get("/ai/admin/trace-metrics"), null, "adminAiTraceMetrics");
      this.adminAiTraceMetrics = body.data || {
        totalRecent: 0,
        fallbackCount: 0,
        permissionDeniedCount: 0,
        noSourceCount: 0,
        sourceBackedCount: 0,
        averageConfidence: null,
        latestTraceId: "",
        intents: {}
      };
    },
    async loadAdminAiTraces() {
      const params = {
        current: this.adminAiTracePage.current,
        pageSize: this.adminAiTracePage.size
      };
      const body = await this.callApi(() => axios.get("/ai/admin/traces", { params }), null, "adminAiTraces");
      this.adminAiTraces = campusApi.unwrapRecords(body).map(item => this.normalizeAiTrace(item));
      this.adminAiTracePage.total = Number(body.total || this.adminAiTraces.length || 0);
      if (this.selectedAiTrace && !this.adminAiTraces.some(item => String(item.messageId) === String(this.selectedAiTrace.messageId))) {
        this.selectedAiTrace = null;
      }
    },
    async refreshAdminAiTraceDashboard() {
      await this.loadAdminAiTraceDashboard();
    },
    selectAiTrace(trace) {
      this.selectedAiTrace = trace ? this.normalizeAiTrace(trace) : null;
    },
    closeAiTraceDetail() {
      this.selectedAiTrace = null;
    },
    normalizeAiTrace(trace) {
      const normalized = Object.assign({}, trace || {});
      normalized.rawResponse = this.normalizeRawResponse(normalized.rawResponse);
      const raw = normalized.rawResponse || {};
      normalized.orchestrator = normalized.orchestrator || raw.orchestrator || "";
      normalized.langGraphNodes = this.normalizeLangGraphNodes(normalized);
      normalized.executionRecords = this.normalizeExecutionRecords(normalized);
      normalized.fallbackRecords = this.normalizeFallbackRecords(normalized);
      normalized.nodeCount = normalized.langGraphNodes.length;
      return normalized;
    },
    normalizeRawResponse(value) {
      if (!value) {
        return {};
      }
      if (typeof value === "string") {
        try {
          return JSON.parse(value);
        } catch (error) {
          return { value };
        }
      }
      if (typeof value === "object") {
        return value;
      }
      return { value };
    },
    normalizeLangGraphNodes(trace) {
      const raw = trace.rawResponse || {};
      const executionTrace = raw.executionTrace || {};
      const nodes = this.firstArray(trace.langGraphNodes, raw.langGraphNodes, raw.lang_graph_nodes, executionTrace.nodes);
      return nodes.map((node, index) => {
        const item = node || {};
        const order = Number(item.order || item.sequence || index + 1);
        return {
          order: Number.isFinite(order) && order > 0 ? order : index + 1,
          nodeName: item.nodeName || item.node_name || item.name || "-",
          status: String(item.status || "").toUpperCase(),
          latencyMs: this.numberOrNull(this.firstPresent(item.latencyMs, item.latency_ms, item.durationMs, item.duration_ms)),
          fallbackReason: item.fallbackReason || item.fallback_reason || "",
          errorType: item.errorType || item.error_type || "",
          toolName: item.toolName || item.tool_name || "",
          toolProtocol: item.toolProtocol || item.tool_protocol || ""
        };
      });
    },
    normalizeExecutionRecords(trace) {
      const raw = trace.rawResponse || {};
      const records = this.firstArray(trace.executionRecords, raw.executionRecords, raw.execution_records);
      return records.map(record => {
        const item = record || {};
        return {
          toolName: item.toolName || item.tool_name || "-",
          toolProtocol: item.toolProtocol || item.tool_protocol || "-",
          success: item.success === true || item.success === "true",
          count: Number(item.count || 0),
          latencyMs: this.numberOrNull(this.firstPresent(item.latencyMs, item.latency_ms, item.durationMs, item.duration_ms)),
          errorType: item.errorType || item.error_type || ""
        };
      });
    },
    normalizeFallbackRecords(trace) {
      const raw = trace.rawResponse || {};
      const records = this.firstArray(trace.fallbackRecords, raw.fallbackRecords, raw.fallback_records);
      return records.map(record => {
        const item = record || {};
        return {
          reason: item.reason || "",
          stage: item.stage || "",
          detail: item.detail || {}
        };
      });
    },
    firstArray() {
      for (let index = 0; index < arguments.length; index += 1) {
        if (Array.isArray(arguments[index])) {
          return arguments[index];
        }
      }
      return [];
    },
    firstPresent() {
      for (let index = 0; index < arguments.length; index += 1) {
        if (arguments[index] !== null && arguments[index] !== undefined && arguments[index] !== "") {
          return arguments[index];
        }
      }
      return null;
    },
    numberOrNull(value) {
      if (value === null || value === undefined || value === "") {
        return null;
      }
      const number = Number(value);
      return Number.isFinite(number) ? number : null;
    },
    aiTraceOrchestratorText(value) {
      const normalized = String(value || "").toLowerCase();
      if (normalized === "langgraph") {
        return "LangGraph";
      }
      if (normalized === "legacy") {
        return "Legacy";
      }
      if (normalized === "local_fallback") {
        return "\u672c\u5730\u515c\u5e95";
      }
      return "\u672a\u77e5";
    },
    aiTraceNodeStatusText(node) {
      const status = String(node && node.status ? node.status : "").toUpperCase();
      if (status === "SUCCESS") {
        return "\u6210\u529f";
      }
      if (status === "FALLBACK") {
        return "\u515c\u5e95";
      }
      if (status === "ERROR") {
        return "\u5931\u8d25";
      }
      if (status === "SKIPPED") {
        return "\u8df3\u8fc7";
      }
      return "\u672a\u77e5";
    },
    aiTraceNodeStatusClass(node) {
      const status = String(node && node.status ? node.status : "").toUpperCase();
      if (status === "ERROR") {
        return "danger";
      }
      if (status === "FALLBACK" || status === "SKIPPED") {
        return "warning";
      }
      if (status === "SUCCESS") {
        return "success";
      }
      return "";
    },
    formatLatencyMs(value) {
      const number = this.numberOrNull(value);
      if (number === null) {
        return "-";
      }
      if (number >= 1000) {
        return (number / 1000).toFixed(2) + " s";
      }
      return Math.round(number * 100) / 100 + " ms";
    },
    formatTraceDetail(value) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      if (typeof value === "string") {
        return value || "-";
      }
      try {
        return JSON.stringify(value);
      } catch (error) {
        return String(value);
      }
    },
    aiTraceStatusText(trace) {
      if (!trace) {
        return "-";
      }
      if (trace.permissionDenied) {
        return "\u6743\u9650\u62d2\u7edd";
      }
      if (trace.fallbackReason) {
        return "\u515c\u5e95";
      }
      return "\u6210\u529f";
    },
    aiTraceStatusClass(trace) {
      if (!trace) {
        return "";
      }
      if (trace.permissionDenied) {
        return "danger-soft";
      }
      if (trace.fallbackReason) {
        return "warning";
      }
      return "success";
    },
    confidenceText(value) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return Math.round(Number(value) * 100) + "%";
    },
    formatJson(value) {
      if (!value) {
        return "{}";
      }
      try {
        return JSON.stringify(value, null, 2);
      } catch (error) {
        return String(value);
      }
    },
    async updateAdminResource(key, request, successText, reload) {
      await this.withSubmit(key, async () => {
        await this.callApi(request, successText, key);
        await reload.call(this);
        await this.loadOperationLogs();
      });
    },
    async loadOperationLogs() {
      const params = { current: 1 };
      if (this.operationLogFilters.appointmentOrderId) {
        params.appointmentOrderId = String(this.operationLogFilters.appointmentOrderId);
      }
      const body = await this.callApi(() => axios.get("/admin/operation-log/page", { params }), null, "operationLogs");
      this.operationLogs = campusApi.unwrapRecords(body);
    },
    async viewAppointmentOrderLogs(order) {
      if (!order || !order.id) {
        return;
      }
      this.adminModule = "logs";
      this.operationLogFilters.appointmentOrderId = order.id;
      await this.loadOperationLogs();
    },
    async resetOperationLogFilters() {
      this.operationLogFilters.appointmentOrderId = "";
      await this.loadOperationLogs();
    },
    formatTime(value) {
      return campusApi.formatTime(value);
    },
    ticketStatus(value) {
      return campusApi.ticketStatus(value);
    },
    servicePointStatus(value) {
      return ({ 0: "\u7981\u7528", 1: "\u542F\u7528", 2: "\u5F85\u5BA1\u6838" })[value] || String(value == null ? "-" : value);
    },
    slotStatus(value) {
      return Number(value) === 1 ? "\u5F00\u653E" : "\u5173\u95ED";
    },
    appointmentOrderStatus(order) {
      if (!order) {
        return "-";
      }
      const statusMap = { 1: "\u5df2\u9884\u7ea6", 2: "\u5df2\u53d6\u6d88", 3: "\u5df2\u5b8c\u6210", 4: "\u5df2\u8fc7\u671f", 5: "\u723d\u7ea6" };
      const statusTextMap = {
        reserved: "\u5df2\u9884\u7ea6",
        canceled: "\u5df2\u53d6\u6d88",
        cancelled: "\u5df2\u53d6\u6d88",
        finished: "\u5df2\u5b8c\u6210",
        expired: "\u5df2\u8fc7\u671f",
        "no show": "\u723d\u7ea6",
        no_show: "\u723d\u7ea6",
        noshow: "\u723d\u7ea6"
      };
      const status = Number(order.status);
      if (statusMap[status]) {
        return statusMap[status];
      }
      const statusText = String(order.statusText || "").trim();
      return statusTextMap[statusText.toLowerCase()] || statusText || "-";
    },
    knowledgeStatus(value) {
      return Number(value) === 1 ? "\u542F\u7528" : "\u7981\u7528";
    },
    roleText(value) {
      return campusApi.displayText(value);
    }
  }
});
