/*
 * Copyright (C) 2015-2017, metaphacts GmbH
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, you can receive a copy
 * of the GNU Lesser General Public License from http://www.gnu.org/
 */

import { Component, createFactory, DOM as D, CSSProperties } from 'react';

export interface Props {
  className?: string;
  style?: CSSProperties;
}

export class PopoverContentComponent extends Component<Props, {}> {
  render() {
    const {className, style} = this.props;
    return D.div({className, style}, this.props.children);
  }
}

export type component = PopoverContentComponent;
export const component = PopoverContentComponent;
export const factory = createFactory(component);
export default component;
