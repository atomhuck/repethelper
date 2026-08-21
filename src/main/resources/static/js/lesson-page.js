(() => {
  document.querySelector(".copy-board-link")?.addEventListener("click", async event => {
    const url = new URL(event.currentTarget.dataset.boardUrl, location.origin).href;
    try {
      await navigator.clipboard.writeText(url);
      event.currentTarget.textContent = "Ссылка скопирована";
      setTimeout(() => { event.currentTarget.textContent = "Скопировать ссылку"; }, 1800);
    } catch (_) {
      window.prompt("Скопируйте ссылку на доску", url);
    }
  });

  document.querySelector(".price-update-form")?.addEventListener("submit", event => {
    const form = event.currentTarget;
    const currentPrice = form.dataset.currentPrice || "";
    const nextPrice = form.querySelector('input[name="priceRubles"]').value;
    const scope = form.querySelector('input[name="scope"]:checked')?.value || "SINGLE";
    if (form.dataset.paid === "true" && scope === "SINGLE" && currentPrice !== nextPrice) {
      if (!window.confirm("Стоимость оплаченного занятия изменится, а отметка «Оплачено» будет сброшена. Продолжить?")) {
        event.preventDefault();
        return;
      }
      form.querySelector(".paid-change-confirmation").value = "true";
    }
  });

  document.querySelectorAll(".upload-form").forEach(form => {
    const input = form.querySelector('input[type="file"]');
    const label = form.querySelector(".file-picker-label");
    if (!input) return;
    form.classList.add("js-auto-upload");
    input.addEventListener("change", () => {
      if (!input.files?.length || form.classList.contains("is-uploading")) return;
      const remaining = Number(form.dataset.remaining || 0);
      if (input.files.length > remaining) {
        window.alert(`Можно добавить ещё не более ${remaining} файлов`);
        input.value = "";
        return;
      }
      if ([...input.files].some(file => file.size > 15 * 1024 * 1024)) {
        window.alert("Размер одного файла не должен превышать 15 МБ");
        input.value = "";
        return;
      }
      form.classList.add("is-uploading");
      if (label) label.textContent = input.files.length === 1
        ? `Загрузка: ${input.files[0].name}` : `Загрузка файлов: ${input.files.length}`;
      form.requestSubmit();
    });
  });
})();
