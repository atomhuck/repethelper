(() => {
  const syncVisualViewport = () => {
    const height = window.visualViewport?.height || window.innerHeight;
    document.documentElement.style.setProperty("--visual-viewport-height", `${Math.round(height)}px`);
  };
  syncVisualViewport();
  window.visualViewport?.addEventListener("resize", syncVisualViewport);
  window.visualViewport?.addEventListener("scroll", syncVisualViewport);

  const digitsBefore = (value, index) => (value || "").slice(0, index).replace(/\D/g, "").length;
  const formattedMoney = digits => digits ? Number(digits).toLocaleString("ru-RU") : "";
  document.querySelectorAll("[data-money-input]").forEach(input => {
    const applyFormat = () => {
      const before = input.value;
      const selection = input.selectionStart ?? before.length;
      const digitOffset = digitsBefore(before, selection);
      const digits = before.replace(/\D/g, "").replace(/^0+(?=\d)/, "");
      const formatted = formattedMoney(digits);
      if (formatted === before) return;
      input.value = formatted;
      let cursor = formatted.length;
      if (digitOffset < digits.length) {
        let seen = 0;
        cursor = 0;
        while (cursor < formatted.length && seen < digitOffset) {
          if (/\d/.test(formatted[cursor])) seen++;
          cursor++;
        }
      }
      input.setSelectionRange(cursor, cursor);
    };
    input.addEventListener("input", applyFormat);
    applyFormat();
  });

  const safeLink = value => {
    try {
      const href = value.toLowerCase().startsWith("www.") ? `https://${value}` : value;
      const url = new URL(href);
      return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password ? href : null;
    } catch (_) { return null; }
  };
  const renderLinkPreview = (target, value) => {
    const preview = target.querySelector(".rich-text");
    if (!preview) return;
    preview.replaceChildren();
    const pattern = /(https?:\/\/[^\s<>]+|www\.[^\s<>]+)/gi;
    let cursor = 0;
    for (const match of value.matchAll(pattern)) {
      preview.append(document.createTextNode(value.slice(cursor, match.index)));
      const visible = match[0].replace(/[.,;:!?)}\]]+$/, "");
      const href = safeLink(visible);
      if (href) {
        const link = document.createElement("a");
        link.href = href; link.target = "_blank"; link.rel = "noopener noreferrer nofollow";
        link.textContent = visible;
        preview.append(link);
      } else preview.append(document.createTextNode(visible));
      preview.append(document.createTextNode(match[0].slice(visible.length)));
      cursor = (match.index || 0) + match[0].length;
    }
    preview.append(document.createTextNode(value.slice(cursor)));
    target.hidden = !value.trim();
  };
  ["homeworkText", "lessonNotesText"].forEach(id => {
    const input = document.getElementById(id);
    if (!input) return;
    let target = document.querySelector(`[data-link-preview="${id}"]`);
    if (!target) {
      target = document.createElement("div");
      target.className = "teacher-link-preview";
      target.dataset.linkPreview = id;
      target.hidden = true;
      const label = document.createElement("small");
      label.textContent = "Предпросмотр · ссылки кликабельны";
      const content = document.createElement("div");
      content.className = "rich-text";
      target.append(label, content);
      input.insertAdjacentElement("afterend", target);
    }
    input.addEventListener("input", () => renderLinkPreview(target, input.value));
  });

  const menus = () => [...document.querySelectorAll("details[data-menu]")];
  const closeMenu = menu => {
    if (!menu?.open) return;
    menu.removeAttribute("open");
    menu.querySelector("summary")?.setAttribute("aria-expanded", "false");
  };

  document.addEventListener("toggle", event => {
    const menu = event.target.closest?.("details[data-menu]");
    if (!menu) return;
    menu.querySelector("summary")?.setAttribute("aria-expanded", String(menu.open));
    if (menu.open) menus().filter(other => other !== menu).forEach(closeMenu);
  }, true);

  document.addEventListener("pointerdown", event => {
    menus().forEach(menu => {
      if (!menu.contains(event.target)) closeMenu(menu);
    });
  });

  document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    const opened = menus().find(menu => menu.open);
    if (opened) {
      event.preventDefault();
      closeMenu(opened);
      opened.querySelector("summary")?.focus();
    }
  });

  document.addEventListener("click", event => {
    const opener = event.target.closest("[data-dialog-open]");
    if (opener) {
      const dialog = document.getElementById(opener.dataset.dialogOpen);
      if (dialog && !dialog.open) {
        dialog.__returnFocus = opener;
        dialog.showModal();
      }
      return;
    }

    const closer = event.target.closest("[data-dialog-close]");
    if (closer) {
      closer.closest("dialog")?.close();
      return;
    }

    const logout = event.target.closest("[data-logout-confirm]");
    if (!logout) return;
    const dialog = document.querySelector("[data-logout-dialog]");
    closeMenu(logout.closest("details[data-menu]"));
    if (dialog && !dialog.open) dialog.showModal();
  });

  document.querySelectorAll("dialog").forEach(dialog => {
    dialog.addEventListener("click", event => {
      if (event.target === dialog) dialog.close();
    });
    dialog.addEventListener("close", () => {
      dialog.__returnFocus?.focus();
      dialog.__returnFocus = null;
    });
  });

  document.addEventListener("click", event => {
    const back = event.target.closest("[data-legal-back]");
    if (!back) return;
    try {
      const previous = new URL(document.referrer);
      if (previous.origin === location.origin && history.length > 1) {
        event.preventDefault();
        history.back();
      }
    } catch (_) { /* Direct opening uses the safe href fallback. */ }
  });
})();
