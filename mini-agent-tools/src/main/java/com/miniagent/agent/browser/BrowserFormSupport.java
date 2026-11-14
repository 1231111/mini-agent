package com.miniagent.agent.browser;

/** Read-only DOM projections. Never call checkValidity/reportValidity: they dispatch application events. */
final class BrowserFormSupport {
    private BrowserFormSupport() {}

    static final String SELECTOR = "input:not([type=hidden]):not([type=submit]):not([type=button]):not([type=reset]):not([type=image]),"
            + "textarea,select,[role=textbox],[role=combobox],[role=listbox],[role=checkbox],[role=radio],[role=switch],[contenteditable=true]";
    static final int MAX_FIELDS = 200;
    static final int MAX_FRAMES = 64;
    /** Only explicit, unique ARIA ownership is actionable; never select an unrelated popup. */
    static final String CUSTOM_SELECT_HELPERS = """
              const customSelect = el => {
                const role = el.getAttribute('role');
                if (!['combobox','listbox'].includes(role)) return null;
                const root = el.getRootNode();
                const ids = [...new Set((el.getAttribute('aria-controls') || el.getAttribute('aria-owns') || '')
                  .split(/\\s+/).filter(Boolean))];
                const targets = role === 'listbox' ? [el] : ids.flatMap(id =>
                  [...root.querySelectorAll('[id]')].filter(node => node.id === id));
                if (targets.length !== 1 || targets[0].getAttribute('role') !== 'listbox') return null;
                const listbox = targets[0];
                const hiddenWithinList = node => {
                  for (let current = node; current && current !== listbox; current = current.parentElement)
                    if (current.matches('[hidden],[inert],[aria-hidden=true]')) return true;
                  return false;
                };
                const nodes = [...listbox.querySelectorAll('[role=option]')]
                  .filter(node => node.closest('[role=listbox]') === listbox);
                const options = nodes.slice(0,200).map(node => {
                  const label = node.getAttribute('aria-label') || node.textContent.trim();
                  return {node,label,value:node.getAttribute('data-value') ?? node.getAttribute('value') ?? label,
                    selected:node.getAttribute('aria-selected') === 'true',
                    selectionKnown:['true','false'].includes(node.getAttribute('aria-selected')),
                    disabled:!!node.closest('[aria-disabled=true],[inert]') || node.matches(':disabled') || hiddenWithinList(node)};
                });
                return {listbox,options,truncated:nodes.length > 200,
                  multiple:listbox.getAttribute('aria-multiselectable') === 'true'};
              };
            """;
    static final String CUSTOM_OPTION_HANDLE = "(el, selection) => {" + CUSTOM_SELECT_HELPERS + """
              const control = customSelect(el);
              if (!control || control.truncated || control.multiple) return null;
              const matches = control.options.filter(option => option[selection.by] === selection.value);
              if (matches.length !== 1 || matches[0].disabled) return null;
              return matches[0].node;
            }
            """;
    static final String CUSTOM_OPTION_READY = "args => {" + CUSTOM_SELECT_HELPERS + """
              const control = customSelect(args.control);
              if (!control || control.truncated || control.multiple || !args.control.isConnected ||
                  !control.options.every(option => option.selectionKnown)) return false;
              const matches = control.options.filter(option => option[args.by] === args.value);
              if (matches.length !== 1 || matches[0].disabled || matches[0].node !== args.target) return false;
              const target = args.target, box = target.getBoundingClientRect(), style = getComputedStyle(target);
              return target.isConnected && !target.closest('[hidden],[inert],[aria-hidden=true],[aria-disabled=true]') &&
                box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
            }
            """;
    static final String READ_FIELD = "el => {" + CUSTOM_SELECT_HELPERS + """
              const text = v => String(v ?? '').slice(0, 8000);
              const tag = el.tagName.toLowerCase();
              const type = tag === 'input' ? el.type : (el.getAttribute('role') || tag);
              const password = type === 'password';
              const native = ['input','textarea','select'].includes(tag);
              const style = getComputedStyle(el), box = el.getBoundingClientRect();
              const visible = box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none' &&
                !el.closest('[aria-hidden=true],[hidden],[inert]');
              const byId = id => el.getRootNode().getElementById?.(id);
              const labelled = (el.getAttribute('aria-labelledby') || '').split(/\\s+/)
                .map(id => byId(id)?.textContent || '').join(' ').trim();
              const label = el.getAttribute('aria-label') || labelled ||
                [...(el.labels || [])].map(l => l.textContent).join(' ').trim() || el.getAttribute('placeholder') || '';
              const value = native ? String(el.value ?? '') : String(el.getAttribute('aria-valuetext') ?? el.textContent ?? '');
              const v = el.validity;
              const validity = v ? Object.fromEntries(['valid','valueMissing','typeMismatch','patternMismatch','tooLong',
                'tooShort','rangeUnderflow','rangeOverflow','stepMismatch','badInput','customError'].map(k => [k, v[k]])) : null;
              const custom = customSelect(el);
              const options = tag === 'select' ? [...el.options] : (custom?.options || []);
              const files = type === 'file' ? [...(el.files || [])].map(f => ({name:f.name,size:f.size,type:f.type})) : [];
              return {
                tag,type,role:el.getAttribute('role') || '',id:el.id || '',name:el.getAttribute('name') || '',label:text(label),
                formId:el.form?.id || '',native,visible,required:!!el.required || el.getAttribute('aria-required') === 'true',
                disabled:!!el.matches(':disabled') || !!el.closest('[aria-disabled=true],[inert]') ||
                  custom?.listbox.getAttribute('aria-disabled') === 'true',readOnly:!!el.readOnly,
                multiple:!!el.multiple || !!custom?.multiple,accept:el.getAttribute('accept') || '',
                customSelectSupported:!!custom && !custom.truncated && !custom.multiple &&
                  custom.options.every(o => o.selectionKnown),
                customPopupHidden:!!custom && !!custom.listbox.closest('[hidden],[inert],[aria-hidden=true]'),
                constraints:Object.fromEntries(['min','max','step','minlength','maxlength','pattern','placeholder','autocomplete']
                  .filter(k => el.hasAttribute(k)).map(k => [k,text(el.getAttribute(k))])),
                value:password || type === 'file' ? null : text(value),valueRedacted:password,
                hasValue:password ? value.length > 0 : null,valueTruncated:!password && value.length > 8000,
                checked:tag === 'input' && ['checkbox','radio'].includes(type) ? !!el.checked :
                  (el.hasAttribute('aria-checked') ? el.getAttribute('aria-checked') : null),
                selectedValues:tag === 'select' ? [...el.selectedOptions].map(o => o.value) :
                  (custom ? custom.options.filter(o => o.selected).map(o => o.value) : null),
                options:options.slice(0,200).map(o => ({label:text(o.label),value:text(o.value),selected:o.selected,
                  disabled:o.disabled || (tag === 'select' && !!o.closest('optgroup:disabled'))})),
                optionsTruncated:options.length > 200 || !!custom?.truncated,
                files,validity,validationMessage:password ? '' : text(el.validationMessage || ''),
                ariaInvalid:el.getAttribute('aria-invalid'),
                describedBy:text((el.getAttribute('aria-describedby') || '').split(/\\s+/)
                  .map(id => byId(id)?.textContent || '').join(' ').trim())
              };
            }
            """;
}
