const campusApi = (() => {
  const baseURL = "/api";
  axios.defaults.baseURL = baseURL;
  axios.defaults.timeout = 12000;

  axios.interceptors.request.use(config => {
    const token = sessionStorage.getItem("campus-token");
    if (token) {
      config.headers.authorization = token;
    }
    return config;
  });

  const errorText = {
    "Duplicate appointment is not allowed": "\u4f60\u5df2\u9884\u7ea6\u8fc7\u8be5\u65f6\u6bb5\uff0c\u8bf7\u52ff\u91cd\u590d\u63d0\u4ea4\u3002",
    "No available quota": "\u5f53\u524d\u65f6\u6bb5\u540d\u989d\u5df2\u7ea6\u6ee1\uff0c\u8bf7\u9009\u62e9\u5176\u4ed6\u65f6\u6bb5\u3002",
    "Appointment slot has expired": "\u8be5\u9884\u7ea6\u65f6\u6bb5\u5df2\u8fc7\u671f\uff0c\u8bf7\u9009\u62e9\u5176\u4ed6\u65f6\u6bb5\u3002",
    "Only reserved appointments can be canceled": "\u53ea\u6709\u5df2\u9884\u7ea6\u72b6\u6001\u7684\u8bb0\u5f55\u624d\u80fd\u53d6\u6d88\u3002",
    "Cancel appointment failed": "\u53d6\u6d88\u9884\u7ea6\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "Ongoing appointments cannot be deleted": "\u9884\u7ea6\u6b63\u5728\u8fdb\u884c\u4e2d\uff0c\u4e0d\u80fd\u5220\u9664\u3002",
    "Delete appointment order failed": "\u9884\u7ea6\u8bb0\u5f55\u5220\u9664\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "No permission to query appointment orders": "\u6ca1\u6709\u67e5\u770b\u9884\u7ea6\u8bb0\u5f55\u7684\u6743\u9650\u3002",
    "No permission to manage this appointment order": "\u6ca1\u6709\u7ba1\u7406\u8be5\u9884\u7ea6\u8bb0\u5f55\u7684\u6743\u9650\u3002",
    "Only reserved appointments can be updated": "\u53ea\u6709\u5df2\u9884\u7ea6\u72b6\u6001\u7684\u8bb0\u5f55\u624d\u80fd\u66f4\u65b0\u3002",
    "Expired appointments cannot be finished": "\u5df2\u8fc7\u671f\u7684\u9884\u7ea6\u4e0d\u80fd\u6807\u8bb0\u4e3a\u5b8c\u6210\u3002",
    "Update appointment order failed": "\u9884\u7ea6\u8bb0\u5f55\u66f4\u65b0\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "Update ticket status failed": "\u5de5\u5355\u72b6\u6001\u66f4\u65b0\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "Close ticket failed": "\u5173\u95ed\u5de5\u5355\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "Assign ticket failed": "\u5de5\u5355\u6d3e\u5355\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u6743\u9650\u6216\u7a0d\u540e\u91cd\u8bd5\u3002",
    "Evaluate ticket failed": "\u5de5\u5355\u8bc4\u4ef7\u5931\u8d25\uff0c\u8bf7\u786e\u8ba4\u5de5\u5355\u72b6\u6001\u540e\u91cd\u8bd5\u3002",
    "Please disable service point before deletion": "\u8bf7\u5148\u7981\u7528\u670d\u52a1\u70b9\uff0c\u518d\u6267\u884c\u5220\u9664\u3002",
    "Cannot delete service point with appointment slots": "\u8be5\u670d\u52a1\u70b9\u5df2\u6709\u9884\u7ea6\u540d\u989d\uff0c\u4e0d\u80fd\u5220\u9664\u3002",
    "Cannot delete service point with appointment orders": "\u8be5\u670d\u52a1\u70b9\u5df2\u6709\u9884\u7ea6\u8bb0\u5f55\uff0c\u4e0d\u80fd\u5220\u9664\u3002",
    "Cannot delete service point with service tickets": "\u8be5\u670d\u52a1\u70b9\u5df2\u6709\u5de5\u5355\u8bb0\u5f55\uff0c\u4e0d\u80fd\u5220\u9664\u3002",
    "Delete service point failed": "\u670d\u52a1\u70b9\u5220\u9664\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "service point not found": "\u670d\u52a1\u70b9\u4e0d\u5b58\u5728\u6216\u5df2\u88ab\u5220\u9664\u3002",
    "Appointment slot not found": "\u9884\u7ea6\u540d\u989d\u4e0d\u5b58\u5728\u6216\u5df2\u88ab\u5220\u9664\u3002",
    "appointment slot id is required": "\u8bf7\u5148\u9009\u62e9\u8981\u7f16\u8f91\u7684\u9884\u7ea6\u540d\u989d\u3002",
    "No permission to query appointment slots": "\u6ca1\u6709\u67e5\u770b\u9884\u7ea6\u540d\u989d\u7684\u6743\u9650\u3002",
    "No permission to manage this appointment slot": "\u6ca1\u6709\u7ba1\u7406\u8be5\u9884\u7ea6\u540d\u989d\u7684\u6743\u9650\u3002",
    "No permission to move this appointment slot to target service point": "\u6ca1\u6709\u5c06\u8be5\u9884\u7ea6\u540d\u989d\u8f6c\u5230\u76ee\u6807\u670d\u52a1\u70b9\u7684\u6743\u9650\u3002",
    "Close appointment slot failed": "\u9884\u7ea6\u540d\u989d\u5173\u95ed\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "Open appointment slot failed": "\u9884\u7ea6\u540d\u989d\u5f00\u542f\u5931\u8d25\uff0c\u8bf7\u5237\u65b0\u540e\u91cd\u8bd5\u3002",
    "Please close appointment slot before deletion": "\u8bf7\u5148\u5173\u95ed\u9884\u7ea6\u540d\u989d\uff0c\u518d\u6267\u884c\u5220\u9664\u3002",
    "Cannot delete appointment slot with appointment orders": "\u8be5\u9884\u7ea6\u540d\u989d\u5df2\u6709\u9884\u7ea6\u8bb0\u5f55\uff0c\u4e0d\u80fd\u5220\u9664\u3002"
  };

  function normalizeError(message) {
    if (!message) return "\u8bf7\u6c42\u5931\u8d25";
    return errorText[message] || message;
  }

  axios.interceptors.response.use(response => {
    const body = response.data;
    if (!body || body.success !== true) {
      return Promise.reject(normalizeError(body && body.errorMsg));
    }
    return body;
  }, error => {
    if (error.response && error.response.status === 401) {
      clearToken();
      // Stop the socket state machine before redirecting so an expired token cannot reconnect.
      window.dispatchEvent(new CustomEvent("campus:auth-failed"));
      if (!/\/campus\/login\.html$/.test(location.pathname)) {
        const redirect = encodeURIComponent(location.pathname + location.search + location.hash);
        location.href = "/campus/login.html?redirect=" + redirect;
      }
      return Promise.reject("\u672a\u767b\u5f55\u6216 token \u5df2\u5931\u6548\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55\u3002");
    }
    if (error.response && error.response.status === 403) {
      return Promise.reject("\u5f53\u524d\u8d26\u53f7\u6ca1\u6709\u8bbf\u95ee\u6743\u9650\u3002");
    }
    return Promise.reject(error.message || "\u7f51\u7edc\u8bf7\u6c42\u5f02\u5e38");
  });

  axios.defaults.paramsSerializer = params => {
    return Object.keys(params || {})
      .filter(key => params[key] !== undefined && params[key] !== null && params[key] !== "")
      .map(key => encodeURIComponent(key) + "=" + encodeURIComponent(params[key]))
      .join("&");
  };

  const statusText = {
    0: "\u5f85\u53d7\u7406",
    1: "\u5df2\u53d7\u7406",
    2: "\u5904\u7406\u4e2d",
    3: "\u5df2\u5b8c\u6210",
    4: "\u5df2\u5173\u95ed",
    5: "\u5df2\u62d2\u7edd"
  };

  const displayTextMap = {
    "Campus Dining": "\u6821\u56ed\u9910\u996e",
    "Express Pickup": "\u5feb\u9012\u53d6\u4ef6",
    "Printing Service": "\u6253\u5370\u670d\u52a1",
    "Repair Service": "\u7ef4\u4fee\u670d\u52a1",
    "Consultation": "\u54a8\u8be2\u670d\u52a1",
    "No.1 Canteen Service Desk": "\u4e00\u53f7\u98df\u5802\u670d\u52a1\u53f0",
    "Campus Express Station": "\u6821\u56ed\u5feb\u9012\u7ad9",
    "Library Printing Point": "\u56fe\u4e66\u9986\u6253\u5370\u70b9",
    "Dorm Repair Center": "\u5bbf\u820d\u7ef4\u4fee\u4e2d\u5fc3",
    "Career Consultation Office": "\u5c31\u4e1a\u54a8\u8be2\u5ba4",
    "East Campus": "\u4e1c\u6821\u533a",
    "Student Center": "\u5b66\u751f\u4e2d\u5fc3",
    "Library": "\u56fe\u4e66\u9986",
    "Dorm Area": "\u5bbf\u820d\u533a",
    "Administration Building": "\u884c\u653f\u697c",
    "No.1 Canteen 1F": "\u4e00\u53f7\u98df\u5802\u4e00\u697c",
    "Student Center North Gate": "\u5b66\u751f\u4e2d\u5fc3\u5317\u95e8",
    "Library 2F": "\u56fe\u4e66\u9986\u4e8c\u697c",
    "Dormitory Area Service Office": "\u5bbf\u820d\u533a\u670d\u52a1\u529e\u516c\u5ba4",
    "Administration Building 305": "\u884c\u653f\u697c 305",
    "Dining window consultation and lost card assistance.": "\u9910\u996e\u7a97\u53e3\u54a8\u8be2\u548c\u6821\u56ed\u5361\u9057\u5931\u534f\u52a9\u3002",
    "Express pickup and parcel issue handling.": "\u63d0\u4f9b\u5feb\u9012\u53d6\u4ef6\u548c\u5305\u88f9\u5f02\u5e38\u5904\u7406\u3002",
    "Self-service printing and binding support.": "\u63d0\u4f9b\u81ea\u52a9\u6253\u5370\u548c\u88c5\u8ba2\u652f\u6301\u3002",
    "Dormitory water, electricity, door and window repair.": "\u5904\u7406\u5bbf\u820d\u6c34\u7535\u3001\u95e8\u7a97\u7ef4\u4fee\u95ee\u9898\u3002",
    "Resume review and career consultation appointment.": "\u63d0\u4f9b\u7b80\u5386\u4fee\u6539\u548c\u5c31\u4e1a\u54a8\u8be2\u9884\u7ea6\u3002",
    "Dorm repair morning visit": "\u5bbf\u820d\u7ef4\u4fee\u4e0a\u5348\u4e0a\u95e8",
    "Career consultation afternoon": "\u5c31\u4e1a\u54a8\u8be2\u4e0b\u5348\u573a",
    "Library printing peak slot": "\u56fe\u4e66\u9986\u6253\u5370\u9ad8\u5cf0\u65f6\u6bb5",
    "Water, electricity, door and window repair appointment.": "\u6c34\u7535\u3001\u95e8\u7a97\u7ef4\u4fee\u9884\u7ea6\u3002",
    "Resume review and career planning consultation.": "\u7b80\u5386\u4fee\u6539\u548c\u804c\u4e1a\u89c4\u5212\u54a8\u8be2\u3002",
    "Printing and binding appointment during exam week.": "\u8003\u8bd5\u5468\u6253\u5370\u548c\u88c5\u8ba2\u9884\u7ea6\u3002",
    "Dorm faucet leaking": "\u5bbf\u820d\u6c34\u9f99\u5934\u6f0f\u6c34",
    "Printer cannot read campus card": "\u6253\u5370\u673a\u65e0\u6cd5\u8bfb\u53d6\u6821\u56ed\u5361",
    "The faucet in dorm room 302 keeps leaking and needs repair.": "\u5bbf\u820d 302 \u6c34\u9f99\u5934\u4e00\u76f4\u6f0f\u6c34\uff0c\u9700\u8981\u7ef4\u4fee\u3002",
    "The library printer failed to read my campus card during payment.": "\u56fe\u4e66\u9986\u6253\u5370\u673a\u5728\u652f\u4ed8\u65f6\u65e0\u6cd5\u8bfb\u53d6\u6211\u7684\u6821\u56ed\u5361\u3002",
    "Dorm faucet leakage repair request.": "\u5bbf\u820d\u6c34\u9f99\u5934\u6f0f\u6c34\u7ef4\u4fee\u8bf7\u6c42\u3002",
    "Printing payment card reading issue.": "\u6253\u5370\u652f\u4ed8\u65f6\u6821\u56ed\u5361\u8bfb\u53d6\u95ee\u9898\u3002",
    "repair": "\u7ef4\u4fee",
    "printing": "\u6253\u5370",
    "express": "\u5feb\u9012",
    "consultation": "\u54a8\u8be2",
    "campus_card": "\u6821\u56ed\u5361",
    "Campus card lost": "\u6821\u56ed\u5361\u9057\u5931",
    "Dormitory repair": "\u5bbf\u820d\u7ef4\u4fee",
    "Printing service": "\u6253\u5370\u670d\u52a1",
    "Express pickup": "\u5feb\u9012\u53d6\u4ef6",
    "Career consultation": "\u5c31\u4e1a\u54a8\u8be2",
    "Phase 3-4 smoke repair ticket": "\u4e09\u56db\u9636\u6bb5\u70df\u6d4b\u7ef4\u4fee\u5de5\u5355",
    "Dorm network is broken and needs repair.": "\u5bbf\u820d\u7f51\u7edc\u6545\u969c\uff0c\u9700\u8981\u7ef4\u4fee\u3002",
    "Library printer card reader failure": "\u56fe\u4e66\u9986\u6253\u5370\u673a\u8bfb\u5361\u5931\u8d25",
    "The first-floor library printer cannot read my campus card. I have class soon and need staff help quickly.": "\u56fe\u4e66\u9986\u4e00\u697c\u6253\u5370\u673a\u65e0\u6cd5\u8bfb\u53d6\u6211\u7684\u6821\u56ed\u5361\u3002\u6211\u9a6c\u4e0a\u8981\u4e0a\u8bfe\uff0c\u9700\u8981\u5de5\u4f5c\u4eba\u5458\u5c3d\u5feb\u534f\u52a9\u3002",
    "campus_faq": "\u6821\u56ed\u5e38\u89c1\u95ee\u9898",
    "admin": "\u7ba1\u7406\u5458",
    "manager": "\u7f51\u70b9\u7ba1\u7406\u5458",
    "student": "\u5b66\u751f",
    "SYSTEM_NOTICE": "\u7cfb\u7edf\u516c\u544a",
    "BUSINESS_REMINDER": "\u4e1a\u52a1\u63d0\u9192",
    "APPROVAL_NOTICE": "\u5ba1\u6279\u901a\u77e5",
    "EXCEPTION_ALERT": "\u5f02\u5e38\u544a\u8b66",
    "SITE_REPLY": "\u7ad9\u5185\u56de\u590d",
    "ALL": "\u5168\u91cf\u7528\u6237",
    "USER": "\u6307\u5b9a\u7528\u6237",
    "ROLE": "\u6307\u5b9a\u89d2\u8272"
  };

  function saveToken(token) {
    if (token) {
      sessionStorage.setItem("campus-token", token);
      return;
    }
    clearToken();
  }

  function getToken() {
    return sessionStorage.getItem("campus-token") || "";
  }

  function clearToken() {
    sessionStorage.removeItem("campus-token");
  }

  async function login(payload) {
    const body = await axios.post("/user/login", payload);
    const data = body.data;
    const token = typeof data === "string" ? data : data && data.token;
    if (!token) {
      throw new Error("\u767b\u5f55\u6210\u529f\uff0c\u4f46\u672a\u8fd4\u56de token\u3002");
    }
    saveToken(token);
    return token;
  }

  async function loadMe() {
    const body = await axios.get("/user/me");
    return body.data || null;
  }

  async function logout() {
    await axios.post("/user/logout");
    clearToken();
  }

  function formatTime(value) {
    if (!value) return "-";
    return String(value).replace("T", " ").slice(0, 19);
  }

  function ticketStatus(value) {
    return statusText[value] || "\u672a\u77e5\u72b6\u6001";
  }

  function displayText(value) {
    if (value === undefined || value === null) return value;
    const text = String(value);
    if (/^\?{3,}$/.test(text)) return "\u5386\u53f2\u4e71\u7801\u6570\u636e\uff0c\u8bf7\u91cd\u65b0\u586b\u5199\u6216\u5173\u95ed\u8be5\u8bb0\u5f55";
    if (/^\?[\?,\s，。]{8,}$/.test(text)) return "\u8be5\u5185\u5bb9\u5df2\u5728\u5165\u5e93\u65f6\u53d8\u6210\u95ee\u53f7\uff0c\u8bf7\u91cd\u65b0\u586b\u5199\u3002";
    return displayTextMap[text] || text;
  }

  function localizeRecord(record) {
    if (!record || typeof record !== "object") return record;
    Object.keys(record).forEach(key => {
      if (typeof record[key] === "string") {
        record[key] = displayText(record[key]);
      }
    });
    return record;
  }

  function unwrapRecords(body) {
    if (!body) return [];
    if (Array.isArray(body.data)) return body.data.map(localizeRecord);
    if (body.data && Array.isArray(body.data.records)) return body.data.records.map(localizeRecord);
    return [];
  }

  return {
    baseURL,
    clearToken,
    login,
    logout,
    loadMe,
    saveToken,
    getToken,
    formatTime,
    ticketStatus,
    displayText,
    unwrapRecords
  };
})();
