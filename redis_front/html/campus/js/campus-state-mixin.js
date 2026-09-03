const campusStateMixin = {
  methods: {
    notify(text, type) {
      this.message = text;
      this.messageType = type || "info";
    },
    debounce(key, action, wait) {
      window.clearTimeout(this.debounceTimers[key]);
      this.$set(this.debounceTimers, key, window.setTimeout(action, wait));
    },
    isLoading(key) {
      return Boolean(this.loadingMap[key]);
    },
    isSubmitting(key) {
      return Boolean(this.submittingMap[key]);
    },
    syncBusyState() {
      this.loading = Object.keys(this.loadingMap).some(item => this.loadingMap[item]) ||
        Object.keys(this.submittingMap).some(item => this.submittingMap[item]);
    },
    setLoading(key, value) {
      if (key) {
        this.$set(this.loadingMap, key, value);
      }
      this.syncBusyState();
    },
    setSubmitting(key, value) {
      if (key) {
        this.$set(this.submittingMap, key, value);
      }
      this.syncBusyState();
    },
    panelError(key) {
      return this.errorMap[key] || "";
    },
    setPanelError(key, value) {
      if (!key) return;
      if (value) {
        this.$set(this.errorMap, key, value);
      } else {
        this.$delete(this.errorMap, key);
      }
    },
    async withSubmit(key, action) {
      if (this.isSubmitting(key)) {
        return null;
      }
      this.setSubmitting(key, true);
      try {
        return await action();
      } finally {
        this.setSubmitting(key, false);
      }
    }
  }
};
