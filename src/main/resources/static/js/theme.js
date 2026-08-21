(() => {
  const storageKey = "repethelper-theme";
  const media = window.matchMedia("(prefers-color-scheme: dark)");
  const choice = () => localStorage.getItem(storageKey) || "system";
  const resolved = () => choice() === "system" ? (media.matches ? "dark" : "light") : choice();

  const apply = () => {
    const theme = resolved();
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    document.querySelector('meta[name="theme-color"]')
      ?.setAttribute("content", theme === "dark" ? "#151716" : "#F6F5F2");
    document.querySelectorAll("[data-theme-choice]").forEach(item => {
      item.setAttribute("aria-pressed", String(item.dataset.themeChoice === choice()));
    });
    document.querySelectorAll("[data-theme-toggle]").forEach(button => {
      button.setAttribute("aria-pressed", String(theme === "dark"));
    });
  };

  const set = value => {
    document.documentElement.classList.add("theme-switching");
    localStorage.setItem(storageKey, value);
    apply();
    window.setTimeout(() => document.documentElement.classList.remove("theme-switching"), 260);
  };

  window.RepetHelperTheme = { apply, get: choice, set };
  apply();
  media.addEventListener?.("change", () => choice() === "system" && apply());

  document.addEventListener("click", event => {
    const choiceButton = event.target.closest("[data-theme-choice]");
    if (choiceButton) set(choiceButton.dataset.themeChoice);
    if (event.target.closest("[data-theme-toggle]")) {
      set(resolved() === "dark" ? "light" : "dark");
    }
  });

  window.addEventListener("pageshow", () => {
    window.setTimeout(() => {
      const active = document.activeElement;
      if (active?.matches?.("[data-no-restore-focus]")) active.blur();
    }, 0);
  });
})();
