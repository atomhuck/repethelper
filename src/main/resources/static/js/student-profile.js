(() => {
  const form = document.querySelector("[data-subscription-create-form]");
  if (!form) return;
  const countInput = form.querySelector("[data-subscription-count]");
  const totalInput = form.querySelector("[data-subscription-total]");
  const preview = form.querySelector("[data-subscription-preview]");
  if (!countInput || !totalInput || !preview) return;
  const updatePreview = () => {
    const count = Number(countInput.value || 0);
    const total = Number(String(totalInput.value || "").replace(/[^0-9]/g, ""));
    if (!Number.isInteger(count) || count < 1 || count > 100 || !Number.isInteger(total) || total < count) {
      preview.textContent = "Укажите от 1 до 100 занятий и общую стоимость не меньше количества занятий.";
      return;
    }
    if (total > count * 1_000_000) {
      preview.textContent = "На одно занятие должно приходиться не больше 1 000 000 ₽.";
      return;
    }
    const base = Math.floor(total / count);
    const remainder = total % count;
    preview.textContent = remainder
      ? `${count} занятий · ${remainder} по ${(base + 1).toLocaleString("ru-RU")} ₽ и ${count - remainder} по ${base.toLocaleString("ru-RU")} ₽`
      : `${count} занятий · по ${base.toLocaleString("ru-RU")} ₽`;
  };
  countInput.addEventListener("input", updatePreview);
  totalInput.addEventListener("input", updatePreview);
  updatePreview();
})();
