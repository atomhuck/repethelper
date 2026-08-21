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
    document.querySelectorAll("details.popover[open]").forEach(popover => {
      if (!popover.contains(event.target)) popover.removeAttribute("open");
    });
  });

  document.addEventListener("keydown", event => {
    if (event.key !== "Escape") return;
    const opened = menus().find(menu => menu.open);
    if (opened) {
      event.preventDefault();
      closeMenu(opened);
      opened.querySelector("summary")?.focus();
      return;
    }
    const popover = document.querySelector("details.popover[open]");
    if (popover) {
      event.preventDefault();
      popover.removeAttribute("open");
      popover.querySelector("summary")?.focus();
    }
  });

  document.addEventListener("click", event => {
    const opener = event.target.closest("[data-dialog-open]");
    if (opener) {
      const dialog = document.getElementById(opener.dataset.dialogOpen);
      if (dialog && !dialog.open) {
        dialog.__returnFocus = opener;
        dialog.showModal();
        document.body.classList.add("dialog-open");
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
    if (dialog && !dialog.open) {
      dialog.showModal();
      document.body.classList.add("dialog-open");
    }
  });

  document.querySelectorAll("dialog").forEach(dialog => {
    dialog.addEventListener("click", event => {
      if (event.target === dialog) dialog.close();
    });
    dialog.addEventListener("close", () => {
      if (!document.querySelector("dialog[open]")) document.body.classList.remove("dialog-open");
      dialog.__returnFocus?.focus();
      dialog.__returnFocus = null;
    });
  });

  const confirmationDialog = (() => {
    const dialog = document.createElement("dialog");
    dialog.className = "action-dialog confirm-dialog";
    dialog.innerHTML = `
      <div class="action-dialog-card">
        <div class="action-dialog-heading">
          <div><p class="eyebrow">Подтверждение</p><h2>Продолжить?</h2></div>
          <button class="icon-button" type="button" data-confirm-cancel aria-label="Закрыть"><svg class="ui-icon" aria-hidden="true"><use href="/brand/ui-icons.svg#x"></use></svg></button>
        </div>
        <p class="action-dialog-copy" data-confirm-message></p>
        <div class="action-dialog-footer">
          <button class="button secondary" type="button" data-confirm-cancel>Отмена</button>
          <button class="button primary" type="button" data-confirm-accept>Продолжить</button>
        </div>
      </div>`;
    document.body.appendChild(dialog);
    return dialog;
  })();

  let pendingConfirmation = null;
  document.addEventListener("submit", event => {
    const form = event.target.closest("form[data-confirm]");
    if (!form || form.dataset.confirmed === "true") return;
    event.preventDefault();
    pendingConfirmation = { form, submitter: event.submitter || null };
    confirmationDialog.querySelector("[data-confirm-message]").textContent = form.dataset.confirm;
    confirmationDialog.showModal();
    document.body.classList.add("dialog-open");
    confirmationDialog.querySelector("[data-confirm-accept]").focus();
  });

  confirmationDialog.addEventListener("click", event => {
    if (event.target === confirmationDialog || event.target.closest("[data-confirm-cancel]")) {
      const returnFocus = pendingConfirmation?.submitter;
      pendingConfirmation = null;
      confirmationDialog.close();
      returnFocus?.focus();
      return;
    }
    if (!event.target.closest("[data-confirm-accept]") || !pendingConfirmation) return;
    const { form, submitter } = pendingConfirmation;
    pendingConfirmation = null;
    confirmationDialog.close();
    form.dataset.confirmed = "true";
    form.requestSubmit(submitter);
    queueMicrotask(() => delete form.dataset.confirmed);
  });
  confirmationDialog.addEventListener("close", () => {
    if (!document.querySelector("dialog[open]")) document.body.classList.remove("dialog-open");
    if (!pendingConfirmation) return;
    const returnFocus = pendingConfirmation.submitter;
    pendingConfirmation = null;
    returnFocus?.focus();
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
