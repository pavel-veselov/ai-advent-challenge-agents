import { useState } from 'react';
import type { ChangeEvent, FormEvent } from 'react';
import { createProfile, deleteProfile, setActiveProfile, updateProfile } from '../api';
import type { Profile } from '../types';

/** Служебное значение опции «Добавить профиль…» в выпадающем списке. */
const ADD_OPTION = '__add__';

interface ProfileSelectProps {
  /** Справочник профилей (глобальный, GET /api/profiles); грузит и перечитывает App. */
  profiles: Profile[];
  /** id активного профиля; null — «Без профиля». */
  activeProfileId: number | null;
  /** Вызывается после успешного создания/удаления/смены — App перечитывает списки. */
  onRefresh: () => void;
}

/**
 * Блок выбора профиля пользователя в правой колонке (над «Настройками LLM»).
 * «Без профиля» (пустое значение) — в запросах к LLM блока профиля нет; выбор
 * существующего профиля сразу делает его активным (PUT /api/profiles/active —
 * глобальная настройка приложения); «Добавить профиль…» открывает диалог создания,
 * созданный профиль сразу становится активным; кнопка «✎» у активного профиля
 * открывает тот же диалог в режиме редактирования (PUT /api/profiles/{id}).
 * Диалог НЕ закрывается кликом по затемнению — только кнопками
 * «Сохранить»/«Отмена» (защита от случайной потери черновика).
 * Справочник перечитывает App (onRefresh).
 * Ошибки показываются инлайном (llm-error-line), без alert.
 */
export default function ProfileSelect({
  profiles,
  activeProfileId,
  onRefresh,
}: ProfileSelectProps) {
  // Поля диалога «Новый профиль / Редактирование»: name обязателен, остальные опциональны.
  const [name, setName] = useState('');
  const [position, setPosition] = useState('');
  const [responseFormat, setResponseFormat] = useState('');
  const [preferences, setPreferences] = useState('');
  const [constraints, setConstraints] = useState('');
  // Режим диалога: null — создание нового профиля; число — редактирование профиля с этим id.
  const [editingId, setEditingId] = useState<number | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [modalBusy, setModalBusy] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);
  // Ошибка смены/удаления активного профиля (под выпадашкой, вместо alert).
  const [selectError, setSelectError] = useState<string | null>(null);

  /** Открыть диалог создания: черновик всегда начинается с чистых полей. */
  const openCreateModal = () => {
    setEditingId(null);
    setName('');
    setPosition('');
    setResponseFormat('');
    setPreferences('');
    setConstraints('');
    setModalError(null);
    setModalOpen(true);
  };

  /** Открыть диалог редактирования: поля предзаполняются значениями профиля. */
  const openEditModal = (p: Profile) => {
    setEditingId(p.id);
    setName(p.name);
    setPosition(p.position ?? '');
    setResponseFormat(p.responseFormat ?? '');
    setPreferences(p.preferences ?? '');
    setConstraints(p.constraints ?? '');
    setModalError(null);
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setModalError(null);
  };

  /** Смена активного профиля выбором в списке; null — «Без профиля». */
  const handleSelect = (e: ChangeEvent<HTMLSelectElement>) => {
    const value = e.target.value;
    if (value === ADD_OPTION) {
      // Служебная опция: открыть диалог создания; контролируемый select при
      // перерисовке вернётся к активному значению.
      setSelectError(null);
      openCreateModal();
      return;
    }
    const next = value === '' ? null : Number(value);
    setSelectError(null);
    setActiveProfile(next)
      .then(() => onRefresh())
      .catch((err: unknown) => {
        setSelectError(err instanceof Error ? err.message : String(err));
      });
  };

  /** Удаление активного профиля (глобальная сущность — подтверждаем явно). */
  const handleDelete = () => {
    if (activeProfileId == null) return;
    const current = profiles.find((p) => p.id === activeProfileId);
    const label = current != null ? `«${current.name}»` : `id ${activeProfileId}`;
    if (!window.confirm(`Удалить профиль ${label}?`)) return;
    setSelectError(null);
    deleteProfile(activeProfileId)
      .then(() => onRefresh())
      .catch((err: unknown) => {
        setSelectError(err instanceof Error ? err.message : String(err));
      });
  };

  /** Сохранение профиля из диалога: создание (POST) или редактирование (PUT).
   *  Созданный профиль сразу становится активным; редактирование активного
   *  профиля сохраняется по текущему id. */
  const handleSubmit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const trimmedName = name.trim();
    if (trimmedName === '' || modalBusy) return;
    const body = {
      name: trimmedName,
      position: position.trim() === '' ? null : position.trim(),
      responseFormat: responseFormat.trim() === '' ? null : responseFormat.trim(),
      preferences: preferences.trim() === '' ? null : preferences.trim(),
      constraints: constraints.trim() === '' ? null : constraints.trim(),
    };
    setModalBusy(true);
    setModalError(null);
    const request =
      editingId == null
        ? createProfile(body).then((created) => setActiveProfile(created.id))
        : updateProfile(editingId, body);
    request
      .then(() => {
        setModalOpen(false);
        onRefresh();
      })
      .catch((err: unknown) => {
        setModalError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        setModalBusy(false);
      });
  };

  return (
    <section className="profile-select-block">
      <header className="llm-settings-block-header">
        <h2>Профиль пользователя</h2>
        <span className="llm-hint">передаётся в запросах к LLM</span>
      </header>
      <div className="profile-row">
        <select
          className="profile-select"
          aria-label="Профиль пользователя"
          title="Персонализация агента: выбранный профиль передаётся в каждом запросе к LLM"
          value={activeProfileId?.toString() ?? ''}
          onChange={handleSelect}
        >
          <option value="">Без профиля</option>
          {profiles.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
          <option value={ADD_OPTION}>Добавить профиль…</option>
        </select>
        {activeProfileId != null ? (
          <>
            <button
              type="button"
              className="profile-edit"
              title="Редактировать активный профиль"
              onClick={() => {
                const current = profiles.find((p) => p.id === activeProfileId);
                if (current != null) openEditModal(current);
              }}
            >
              ✎
            </button>
            <button
              type="button"
              className="profile-del"
              title="Удалить активный профиль"
              onClick={handleDelete}
            >
              ✕
            </button>
          </>
        ) : null}
      </div>
      {selectError != null ? (
        <div className="llm-error-line" role="alert">
          не удалось применить профиль: {selectError}
        </div>
      ) : null}

      {modalOpen ? (
        // Без onClick на затемнении: диалог закрывается только кнопками
        // «Сохранить»/«Отмена» — клик мимо окна не теряет черновик.
        <div className="profile-modal-backdrop">
          <form className="profile-modal" onSubmit={handleSubmit}>
            <div className="profile-modal-title">
              {editingId == null ? 'Новый профиль' : 'Редактирование профиля'}
            </div>
            <label className="profile-field">
              <span className="profile-field-label">Название</span>
              <input
                className="profile-input"
                placeholder="Например: Профиль 1"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={120}
                autoFocus
              />
            </label>
            <label className="profile-field">
              <span className="profile-field-label">Стиль</span>
              <input
                className="profile-input"
                placeholder="Например: кратко и дружелюбно, без канцелярита"
                value={position}
                onChange={(e) => setPosition(e.target.value)}
                maxLength={2000}
              />
            </label>
            <label className="profile-field">
              <span className="profile-field-label">Формат ответа</span>
              <input
                className="profile-input"
                placeholder="Например: кратко, списком, без воды"
                value={responseFormat}
                onChange={(e) => setResponseFormat(e.target.value)}
                maxLength={2000}
              />
            </label>
            <label className="profile-field">
              <span className="profile-field-label">Предпочтения</span>
              <textarea
                className="profile-textarea"
                placeholder="Например: я люблю когда код объясняют по шагам"
                value={preferences}
                onChange={(e) => setPreferences(e.target.value)}
                maxLength={2000}
                rows={2}
              />
            </label>
            <label className="profile-field">
              <span className="profile-field-label">Ограничения</span>
              <textarea
                className="profile-textarea"
                placeholder="Например: не используй маркдаун, отвечай только по-русски"
                value={constraints}
                onChange={(e) => setConstraints(e.target.value)}
                maxLength={2000}
                rows={2}
              />
            </label>
            {modalError != null ? (
              <div className="llm-error-line" role="alert">
                не удалось сохранить профиль: {modalError}
              </div>
            ) : null}
            <div className="profile-modal-actions">
              <button
                type="submit"
                className="project-btn"
                disabled={modalBusy || name.trim() === ''}
              >
                {modalBusy ? 'Сохранение…' : 'Сохранить'}
              </button>
              <button
                type="button"
                className="project-btn"
                onClick={closeModal}
                disabled={modalBusy}
              >
                Отмена
              </button>
            </div>
          </form>
        </div>
      ) : null}
    </section>
  );
}
