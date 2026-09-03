const campusUiConfig = (() => {
  const navItems = [
    { key: "service", label: "\u670d\u52a1\u7f51\u70b9", icon: "\u670d" },
    { key: "board", label: "\u4e92\u52a8\u7559\u8a00\u677f", icon: "\u5e16" },
    { key: "appointment", label: "\u9884\u7ea6\u529e\u7406", icon: "\u7ea6" },
    { key: "inbox", label: "\u7ad9\u5185\u6536\u4ef6\u7bb1", icon: "\u4fe1" },
    { key: "ticket", label: "\u670d\u52a1\u5de5\u5355", icon: "\u5355" },
    { key: "ai", label: "\u667a\u80fd\u52a9\u624b", icon: "\u667a" },
    { key: "admin", label: "\u7ba1\u7406\u540e\u53f0", icon: "\u7ba1" }
  ];

  const titleMap = {
    service: "\u6821\u56ed\u670d\u52a1\u7f51\u70b9",
    board: "\u7f51\u70b9\u4e92\u52a8\u7559\u8a00\u677f",
    appointment: "\u9884\u7ea6\u529e\u7406",
    inbox: "\u7ad9\u5185\u6536\u4ef6\u7bb1",
    ticket: "\u670d\u52a1\u5de5\u5355",
    ai: "\u6821\u56ed\u667a\u80fd\u52a9\u624b",
    admin: "\u7ba1\u7406\u540e\u53f0"
  };

  const noteMap = {
    service: "\u6309\u5206\u7c7b\u67e5\u770b\u6821\u56ed\u670d\u52a1\u7f51\u70b9\uff0c\u5feb\u901f\u8fdb\u5165\u9884\u7ea6\u6216\u5de5\u5355\u6d41\u7a0b\u3002",
    board: "\u56f4\u7ed5\u5177\u4f53\u670d\u52a1\u7f51\u70b9\u5c55\u793a\u4e3b\u5e16\u3001\u697c\u4e2d\u697c\u548c\u7ba1\u7406\u5458\u56de\u590d\u3002",
    appointment: "\u67e5\u770b\u53ef\u9884\u7ea6\u65f6\u6bb5\uff0c\u8ddf\u8e2a\u6211\u7684\u9884\u7ea6\u8bb0\u5f55\u548c\u5f02\u6b65\u843d\u5e93\u7ed3\u679c\u3002",
    inbox: "\u67e5\u6536\u7cfb\u7edf\u516c\u544a\u3001\u4e1a\u52a1\u63d0\u9192\u3001\u5ba1\u6279\u901a\u77e5\u548c\u7559\u8a00\u56de\u590d\uff0c\u652f\u6301\u5df2\u8bfb\u3001\u6807\u661f\u548c\u5b9e\u65f6\u63a8\u9001\u3002",
    ticket: "\u521b\u5efa\u670d\u52a1\u5de5\u5355\uff0c\u67e5\u770b\u667a\u80fd\u6458\u8981\u548c\u5904\u7406\u8fdb\u5ea6\u3002",
    ai: "\u901a\u8fc7\u667a\u80fd\u52a9\u624b\u83b7\u53d6\u6821\u56ed\u670d\u52a1\u6307\u5f15\u548c\u5efa\u8bae\u3002",
    admin: "\u9762\u5411\u7ba1\u7406\u5458\u7684\u5de5\u5355\u5904\u7406\u548c\u64cd\u4f5c\u65e5\u5fd7\u89c6\u56fe\u3002"
  };

  const ticketCategories = [
    { id: 1, name: "\u6821\u56ed\u9910\u996e" },
    { id: 2, name: "\u5feb\u9012\u53d6\u4ef6" },
    { id: 3, name: "\u6253\u5370\u670d\u52a1" },
    { id: 4, name: "\u7ef4\u4fee\u670d\u52a1" },
    { id: 5, name: "\u54a8\u8be2\u670d\u52a1" },
    { id: 6, name: "\u6821\u56ed\u5361\u52a1" },
    { id: 7, name: "\u7f51\u7edc\u670d\u52a1" }
  ];

  function validateTicket(form, field) {
    const title = (form.title || "").trim();
    const content = (form.content || "").trim();
    const contactPhone = (form.contactPhone || "").trim();
    const detailAddress = (form.detailAddress || "").trim();
    const errors = {};

    if (!field || field === "servicePointId") {
      if (!form.servicePointId) {
        errors.servicePointId = "\u8bf7\u9009\u62e9\u670d\u52a1\u7f51\u70b9\u3002";
      }
    }

    if (!field || field === "categoryId") {
      if (!form.categoryId) {
        errors.categoryId = "\u8bf7\u9009\u62e9\u670d\u52a1\u5206\u7c7b\u3002";
      }
    }

    if (!field || field === "title") {
      if (!title) {
        errors.title = "\u8bf7\u586b\u5199\u5de5\u5355\u6807\u9898\u3002";
      } else if (title.length > 50) {
        errors.title = "\u6807\u9898\u4e0d\u80fd\u8d85\u8fc7 50 \u4e2a\u5b57\u3002";
      }
    }

    if (!field || field === "content") {
      if (!content) {
        errors.content = "\u8bf7\u63cf\u8ff0\u9700\u8981\u5904\u7406\u7684\u95ee\u9898\u3002";
      } else if (content.length < 8) {
        errors.content = "\u95ee\u9898\u63cf\u8ff0\u81f3\u5c11\u9700\u8981 8 \u4e2a\u5b57\u3002";
      } else if (content.length > 500) {
        errors.content = "\u95ee\u9898\u63cf\u8ff0\u4e0d\u80fd\u8d85\u8fc7 500 \u4e2a\u5b57\u3002";
      }
    }

    if (!field || field === "contactPhone") {
      if (!contactPhone) {
        errors.contactPhone = "\u8bf7\u586b\u5199\u8054\u7cfb\u65b9\u5f0f\u3002";
      } else if (contactPhone.length > 32) {
        errors.contactPhone = "\u8054\u7cfb\u65b9\u5f0f\u4e0d\u80fd\u8d85\u8fc7 32 \u4e2a\u5b57\u7b26\u3002";
      }
    }

    if (!field || field === "detailAddress") {
      if (!detailAddress) {
        errors.detailAddress = "\u8bf7\u586b\u5199\u8be6\u7ec6\u5730\u5740\u3002";
      } else if (detailAddress.length > 255) {
        errors.detailAddress = "\u8be6\u7ec6\u5730\u5740\u4e0d\u80fd\u8d85\u8fc7 255 \u4e2a\u5b57\u3002";
      }
    }

    return errors;
  }

  return {
    navItems,
    titleMap,
    noteMap,
    ticketCategories,
    validateTicket
  };
})();
